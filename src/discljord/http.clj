(ns discljord.http
  (:require [clojure.spec.alpha :as s]
            [discljord.specs :as ds]))

(defn api-url
  "Takes an endpoint from Discord's API (with a starting '/'), and returns
  a URL for that Discord endpoint.

  For example: (api-url (str \"/channels/\" channel-id))"
  [endpoint]
  (str "https://discordapp.com/api/v6" endpoint))
(s/fdef api-url
  :args (s/cat :url ::ds/url)
  :ret ::ds/url)

(def gateway-url
  "URL used to retrieve the gateway for the bot."
  "https://discordapp.com/api/gateway/bot?v=6&encoding=json")
