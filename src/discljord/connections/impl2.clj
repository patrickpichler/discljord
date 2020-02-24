(ns discljord.connections.impl2
  "Implementation of websocket connections to Discord."
  (:require
   [clojure.core.async :as a]
   [clojure.data.json :as json]
   [discljord.http :refer [gateway-url]]
   [discljord.util :refer [json-keyword clean-json-input]]
   [gniazdo.core :as ws]
   [org.httpkit.client :as http]
   [taoensso.timbre :as log])
  (:import
   (org.eclipse.jetty.websocket.client
    WebSocketClient)
   (org.eclipse.jetty.util.ssl
    SslContextFactory)))

(def buffer-size
  "The suggested size of a buffer; default: 4MiB."
  4194304)

(defmulti handle-websocket-event
  "Updates a `shard` based on shard events.
  Takes a `shard` and a shard event vector and returns a map of the new state of
  the shard and zero or more events to process."
  (fn [shard [event-type & args]]
    event-type))

(def new-session-stop-code?
  "Set of stop codes after which a resume isn't possible."
  #{4003 4004 4007 4009})

(defn should-resume?
  "Returns if a shard should try to resume."
  [shard]
  (when (:stop-code shard)
    (and (not (new-session-stop-code? (:stop-code shard)))
         (:seq shard)
         (:session-id shard))))

(defmethod handle-websocket-event :connect
  [shard [_]]
  {:shard shard
   :effects [(if (should-resume? shard)
               [:resume]
               [:identify])]})

(def ^:dynamic *stop-on-fatal-code*
  "Bind to to true to disconnect the entire bot after a fatal stop code."
  false)

(def fatal-code?
  "Set of stop codes which after recieving, discljord will disconnect all shards."
  #{4001 4002 4003 4004 4005 4008 4010})

(def re-shard-stop-code?
  "Stop codes which Discord will send when the bot needs to be re-sharded."
  #{4011})

(defmethod handle-websocket-event :disconnect
  [shard [_ stop-code msg]]
  (if shard
    {:shard (assoc shard
                   :stop-code stop-code
                   :disconnect-msg msg)
     :effects [(cond
                 (re-shard-stop-code? stop-code) [:re-shard]
                 (and *stop-on-fatal-code*
                      (fatal-code? stop-code))   [:disconnect]
                 :otherwise                      [:reconnect])]}
    {:shard nil
     :effects []}))

(defmethod handle-websocket-event :error
  [shard [_ err]]
  {:shard shard
   :effects [[:error err]]})

(defmethod handle-websocket-event :send-debug-effect
  [shard [_ & effects]]
  {:shard shard
   :effects (vec effects)})

(def ^:private payload-id->payload-key
  "Map from payload type ids to the payload keyword."
  {0 :event-dispatch
   1 :heartbeat
   7 :reconnect
   9 :invalid-session
   10 :hello
   11 :heartbeat-ack})

(defmulti handle-payload
  "Update a `shard` based on a message.
  Takes a `shard` and `msg` and returns a map with a :shard and an :effects
  vector."
  (fn [shard msg]
    (payload-id->payload-key (:op msg))))

(defmethod handle-websocket-event :message
  [shard [_ msg]]
  (handle-payload shard (clean-json-input (json/read-str msg))))

(defmulti handle-discord-event
  "Handles discord events for a specific shard, specifying effects."
  (fn [shard event-type event]
    event-type))

(defmethod handle-discord-event :default
  [shard event-type event]
  {:shard shard
   :effects [[:send-discord-event event-type event]]})

(defmethod handle-discord-event :ready
  [shard event-type event]
  {:shard (assoc shard :session-id (:session-id event))
   :effects [[:send-discord-event event-type event]]})

(defmethod handle-payload :event-dispatch
  [shard {:keys [d t s] :as msg}]
  (handle-discord-event (assoc shard :seq s) (json-keyword t) d))

(defmethod handle-payload :heartbeat
  [shard msg]
  {:shard shard
   :effects [[:send-heartbeat]]})

(defmethod handle-payload :reconnect
  [shard {d :d}]
  {:shard shard
   :effects [[:reconnect]]})

(defmethod handle-payload :invalid-session
  [shard {d :d}]
  {:shard (assoc (dissoc shard
                         :session-id
                         :seq)
                 :invalid-session true)
   :effects [[:reconnect]]})

(defmethod handle-payload :hello
  [shard {{:keys [heartbeat-interval]} :d}]
  {:shard shard
   :effects [[:start-heartbeat heartbeat-interval]]})

(defmethod handle-payload :heartbeat-ack
  [shard msg]
  {:shard (assoc shard :ack true)
   :effects []})

(defn connect-websocket!
  "Connect a websocket to the `url` that puts all events onto the `event-ch`.
  Events are represented as vectors with a keyword for the event type and then
  event data as the rest of the vector based on the type of event.

  | Type          | Data |
  |---------------+------|
  | `:connect`    | None.
  | `:disconnect` | Stop code, string message.
  | `:error`      | Error value.
  | `:message`    | String message."
  [buffer-size url event-ch]
  (log/debug "Starting websocket of size" buffer-size "at url" url)
  (let [client (WebSocketClient. (doto (SslContextFactory.)
                                   (.setEndpointIdentificationAlgorithm "HTTPS")))]
    (doto (.getPolicy client)
      (.setMaxTextMessageSize buffer-size)
      (.setMaxBinaryMessageSize buffer-size))
    (doto client
      (.setMaxTextMessageBufferSize buffer-size)
      (.setMaxBinaryMessageBufferSize buffer-size)
      (.start))
    (ws/connect
        url
      :client client
      :on-connect (fn [_]
                    (log/trace "Websocket connected")
                    (a/put! event-ch [:connect]))
      :on-close (fn [stop-code msg]
                  (log/debug "Websocket closed with code:" stop-code "and message:" msg)
                  (a/put! event-ch [:disconnect stop-code msg]))
      :on-error (fn [err]
                  (log/warn "Websocket errored" err)
                  (a/put! event-ch [:error err]))
      :on-receive (fn [msg]
                    (log/trace "Websocket recieved message:" msg)
                    (a/put! event-ch [:message msg])))))

(defmulti handle-shard-fx!
  "Processes an `event` on a given `shard` for side effects.
  Returns a map with the new :shard and bot-level :effects to process."
  (fn [heartbeat-ch url token shard event]
    (first event)))

(defn step-shard!
  "Starts a process to step a `shard`, handling side-effects.
  Returns a channel which will have a map with the new `:shard` and a vector of
  `:effects` for the entire bot to respond to placed on it after the next item
  the socket may respond to occurs."
  [shard url token]
  (log/trace "Stepping shard" (:id shard) shard)
  (let [{:keys [event-ch websocket heartbeat-ch communication-ch stop-ch] :or {heartbeat-ch (a/chan)}} shard]
    (a/go
      (a/alt!
        stop-ch (do
                  (when heartbeat-ch
                    (a/close! heartbeat-ch))
                  (a/close! communication-ch)
                  (when websocket
                    (ws/close websocket))
                  (log/info "Disconnecting shard"
                            (:id shard)
                            "and closing connection")
                  {:shard nil
                   :effects []})
        communication-ch ([[event-type & event-data :as value]]
                          (log/debug "Recieved communication value" value "on shard" (:id shard))
                          ;; TODO(Joshua): consider extracting this to a multimethod
                          (case event-type
                            :connect (let [event-ch (a/chan 100)]
                                       (log/info "Connecting shard" (:id shard))
                                       (a/close! heartbeat-ch)
                                       {:shard (assoc (dissoc shard :heartbeat-ch)
                                                      :websocket (connect-websocket! buffer-size url event-ch)
                                                      :event-ch event-ch)
                                        :effects []})
                            (do
                              ;; TODO(Joshua): Send a message over the websocket
                              (log/trace "Sending a message over the websocket")
                              {:shard shard
                               :effects []})))
        heartbeat-ch (if (:ack shard)
                       (do (log/trace "Sending heartbeat payload on shard" (:id shard))
                           (ws/send-msg websocket
                                        (json/write-str {:op 1
                                                         :d (:seq shard)}))
                           {:shard (dissoc shard :ack)
                            :effects []})
                       (let [event-ch (a/chan 100)]
                         (try
                           (when websocket
                             (ws/close websocket))
                           (catch Exception e
                             (log/debug "Websocket failed to close during reconnect" e)))
                         (log/info "Reconnecting due to zombie heartbeat on shard" (:id shard))
                         (a/close! heartbeat-ch)
                         {:shard (assoc (dissoc shard :heartbeat-ch)
                                        :websocket (connect-websocket! buffer-size url event-ch)
                                        :event-ch event-ch)
                          :effects []}))
        event-ch ([event]
                  (let [{:keys [shard effects]} (handle-websocket-event shard event)
                        shard-map (reduce
                                   (fn [{:keys [shard effects]} new-effect]
                                     (let [old-effects effects
                                           {:keys [shard effects]}
                                           (handle-shard-fx! heartbeat-ch url token shard new-effect)
                                           new-effects (vec (concat old-effects effects))]
                                       {:shard shard
                                        :effects new-effects}))
                                   {:shard shard
                                    :effects []}
                                   effects)]
                    shard-map))
        :priority true))))

(defn get-websocket-gateway
  "Gets the shard count and websocket endpoint from Discord's API.

  Takes the `url` of the gateway and the `token` of the bot.

  Returns a map with the keys :url, :shard-count, and :session-start limit, or
  nil in the case of an error."
  [url token]
  (if-let [result
           (try
             (when-let [response (:body @(http/get url
                                                   {:headers
                                                    {"Authorization" token}}))]
               (when-let [json-body (clean-json-input (json/read-str response))]
                 {:url (:url json-body)
                  :shard-count (:shards json-body)
                  :session-start-limit (:session-start-limit json-body)}))
             (catch Exception e
               (log/error e "Failed to get websocket gateway")
               nil))]
    (when (:url result)
      result)))

(defn make-shard
  "Creates a new shard with the given `id` and `shard-count`."
  [id shard-count]
  {:id id
   :count shard-count
   :event-ch (a/chan 100)
   :communication-ch (a/chan 100)
   :stop-ch (a/chan 1)})

(defn after-timeout!
  "Calls a function of no arguments after the given `timeout`.
  Returns a channel which will have the return value of the function put on it."
  [f timeout]
  (a/go (a/<! (a/timeout timeout))
        (f)))

(defmethod handle-shard-fx! :start-heartbeat
  [heartbeat-ch url token shard [_ heartbeat-interval]]
  (let [heartbeat-ch (a/chan (a/sliding-buffer 1))]
    (log/debug "Starting a heartbeat with interval" heartbeat-interval "on shard" (:id shard))
    (a/put! heartbeat-ch :heartbeat)
    (a/go-loop []
      (a/<! (a/timeout heartbeat-interval))
      (when (a/>! heartbeat-ch :heartbeat)
        (log/trace "Requesting heartbeat on shard" (:id shard))
        (recur)))
    {:shard (assoc shard
                   :heartbeat-ch heartbeat-ch
                   :ack true)
     :effects []}))

(defmethod handle-shard-fx! :send-heartbeat
  [heartbeat-ch url token shard event]
  (when heartbeat-ch
    (log/trace "Responding to requested heartbeat signal")
    (a/put! heartbeat-ch :heartbeat))
  {:shard shard
   :effects []})

(defmethod handle-shard-fx! :identify
  [heartbeat-ch url token shard event]
  (log/debug "Sending identify payload for shard" (:id shard))
  (ws/send-msg (:websocket shard)
               (json/write-str {:op 2
                                :d {:token token
                                    :properties {"$os" "linux"
                                                 "$browser" "discljord"
                                                 "$device" "discljord"}
                                    :compress false
                                    :large_threshold 50
                                    :shard [(:id shard) (:count shard)]}}))
  {:shard shard
   :effects []})

(defmethod handle-shard-fx! :resume
  [heartbeat-ch url token shard event]
  (log/debug "Sending resume payload for shard" (:id shard))
  (let [event-ch (a/chan 100)
        shard (assoc shard :websocket (connect-websocket! buffer-size url event-ch))]
    (ws/send-msg (:websocket shard)
                 (json/write-str {:op 6
                                  :d {:token token
                                      :session_id (:session-id shard)
                                      :seq (:seq shard)}}))
    {:shard shard
     :effects []}))

;; TODO(Joshua): Make this actually send an event to the controlling process and kill off this shard
(defmethod handle-shard-fx! :reconnect
  [heartbeat-ch url token shard event]
  (let [event-ch (a/chan 100)]
    (when (:invalid-session shard)
      (log/warn "Got invalid session payload, disconnecting shard" (:id shard)))
    (when (:stop-code shard)
      (log/debug "Shard" (:id shard)
                 "has disconnected with stop-code"
                 (:stop-code shard) "and message" (:disconnect-msg shard)))
    (when (:heartbeat-ch shard)
      (a/close! (:heartbeat-ch shard)))
    (log/debug "Reconnecting shard" (:id shard))
    {:shard (assoc (dissoc shard
                           :invalid-session
                           :stop-code
                           :disconnect-msg
                           :heartbeat-ch)
                   :websocket (connect-websocket! buffer-size url event-ch)
                   :event-ch event-ch)
     :effects []}))

(defmethod handle-shard-fx! :re-shard
  [heartbeat-ch url token shard event]
  {:shard shard
   :effects [[:re-shard]]})

(defmethod handle-shard-fx! :error
  [heartbeat-ch url token shard [_ err]]
  (log/error err "Error encountered on shard" (:id shard))
  {:shard shard
   :effects []})

(defmethod handle-shard-fx! :send-discord-event
  [heartbeat-ch url token shard [_ event-type event]]
  (log/trace "Shard" (:id shard) "recieved discord event:" event)
  {:shard shard
   :effects [[:discord-event event-type event]]})

(defmulti handle-bot-fx!
  "Handles a bot-level side effect triggered by a shard.
  This method should never block, and should not do intense computation. Takes a
  place to output events to the library user, the url to connect sockets, the
  bot's token, the vector of shards, a vector of channels which resolve to the
  shard's next state, the index of the shard the effect is from, and the effect.
  Returns a vector of the vector of shards and the vector of shard channels."
  (fn [output-ch url token shards shard-chs shard-idx effect]
    (first effect)))

(defmulti handle-communication!
  "Handles communicating to the `shards`.
  Takes an `event` vector, a vector of `shards`, and a vector of channels which
  resolve to each shard's next state, and returns a vector of the vector of
  shards and the vector of channels."
  (fn [shards shard-chs event]
    (first event)))

(defn- index-of
  "Fetches the index of the first occurent of `elt` in `coll`.
  Returns nil if it's not found."
  [elt coll]
  (first (first (filter (comp #{elt} second) (map-indexed vector coll)))))

(defn connect-shards!
  "Connects a set of shards with the given `shard-ids`."
  [output-ch communication-ch url token shard-count shard-ids]
  (let [shards (mapv #(make-shard % shard-count) shard-ids)]
    (a/go-loop [shards shards
                shard-chs (mapv #(step-shard! % url token) shards)]
      (when (some identity shard-chs)
        (reset! user/shards shards)
        (log/trace "Waiting for a shard to complete a step")
        (let [[v p] (a/alts! (conj (remove nil? shard-chs)
                                   communication-ch))]
          (if (= communication-ch p)
            (let [[shards shard-chs] (handle-communication! shards shard-chs v)]
              (recur shards shard-chs))
            (let [idx (index-of p shard-chs)
                  effects (:effects v)
                  shards (assoc shards idx (:shard v))
                  shard-chs (assoc shard-chs idx (when (:shard v)
                                                   (step-shard! (:shard v) url token)))
                  [shards shard-chs] (reduce (fn [[shards shard-chs] effect]
                                               (handle-bot-fx! output-ch
                                                               url token
                                                               shards shard-chs
                                                               idx effect))
                                             [shards shard-chs]
                                             effects)]
              (recur shards shard-chs))))))
    (doseq [[idx shard] (map-indexed vector shards)]
      (after-timeout! #(a/put! (:communication-ch shard) [:connect]) (* idx 5100)))
    (after-timeout! #(a/put! output-ch [:connected-all-shards]) (+ (* (dec (count shard-ids)) 5100)
                                                                   100))
    nil))

(defmethod handle-bot-fx! :discord-event
  [output-ch url token shards shard-chs shard-idx [_ event-type event]]
  (a/put! output-ch [event-type event])
  [shards shard-chs])

(def ^:dynamic *handle-re-shard*
  "Determines if the bot will re-shard on its own, or require user coordination.
  If bound to true and a re-shard occurs, the bot will make a request to discord
  for the new number of shards to connect with and then connect them. If bound
  to false, then a :re-shard event will be sent to the user library and all
  shards will be disconnected."
  true)

(defmethod handle-bot-fx! :re-shard
  [output-ch url token shards shard-chs shard-idx [_ event-type event]]
  (log/info "Stopping all current shards to prepare for re-shard.")
  (a/put! output-ch [:re-shard])
  (run! #(a/put! (:stop-ch %) :disconnect) shards)
  (run! #(a/<!! (step-shard! % url token))
        (remove nil?
                (map (comp :shard a/<!!) shard-chs)))
  (if *handle-re-shard*
    (let [{:keys [url shard-count session-start-limit]} (get-websocket-gateway gateway-url token)]
      (when (> shard-count (:remaining session-start-limit))
        (log/fatal "Asked to re-shard client, but too few session starts remain.")
        (throw (ex-info "Unable to re-shard client, too few session starts remaining."
                        {:token token
                         :shards-requested shard-count
                         :remaining-starts session-start-limit})))
      (let [shards (mapv #(make-shard % shard-count) (range shard-count))
            shard-chs (mapv #(step-shard! % url token) shards)]
        (doseq [[idx shard] (map-indexed vector shards)]
          (after-timeout! #(a/put! (:communication-ch shard) [:connect]) (* idx 5100)))
        (after-timeout! #(a/put! output-ch [:connected-all-shards]) (+ (* (dec shard-count) 5100)
                                                                       100))
        [shards shard-chs]))
    [nil nil]))

(defmethod handle-communication! :disconnect
  [shards shard-chs _]
  (run! #(a/put! (:stop-ch %) :disconnect) shards)
  [shards shard-chs])

(defmethod handle-communication! :send-debug-event
  [shards shard-chs [_ shard-id event]]
  (a/put! (:event-ch (get shards shard-id)) event)
  [shards shard-chs])
