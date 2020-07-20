(defproject org.suskalo/discljord "1.1.1"
  :description " A Clojure wrapper library for the Discord API, with full API coverage (except voice, for now), and high scalability."
  :url "https://github.com/IGJoshua/discljord"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "1.2.603"]
                 [org.clojure/data.json "1.0.0"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.martinklepsch/clj-http-lite "0.4.3"]
                 [stylefruits/gniazdo "1.1.4"]]
  :target-path "target/%s"
  :jar-name "discljord-%s.jar"
  :deploy-branches ["master" "release" "hotfix"]
  :profiles {:dev {:dependencies [[http-kit "2.4.0-alpha6"]
                                  [clj-http-lite-fake "0.0.1-SNAPSHOT"]
                                  [ch.qos.logback/logback-classic "1.2.3"]]
                   :plugins [[lein-codox "0.10.7"]]
                   :exclusions [http-kit]
                   :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/slf4j-factory"]}})
