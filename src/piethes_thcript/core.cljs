(ns piethes-thcript.core
  (:require [goog.object :as o]
            [piethes-thcript.funcs [log i18n
                                    run-message-monitors permission-level
                                    handle-message
                                    parse-command handle-command]]
            [piethes-thcript.async :refer [await-p]]
            [piethes-thcript.utils.load-events :refer [load-events]]))

(def Discord (js/require "discord.js"))
(def path (js/require "path"))

(def Config (js/require "./classes/Config.js"))

(defn- extend-client! [client]
  ;; Extend client
  (doseq [k [:helpStructure :commands :aliases
             :commandInhibitors :messageMonitors :providers]]
    (o/set client k {}))
  ;; Extend client with native Discord.js functions for use in our pieces
  (o/set client :methods {:Collection (.-Collection Discord)
                          :Embed (.-RichEmbed Discord)
                          :MessageCollector (.-MessageCollector Discord)
                          :Webhook (.-WebhookClient Discord)
                          :escapeMarkdown (.-escapeMarkdown Discord)
                          :splitMessage (.-splitMessage Discord)})
  ;; Various other properties
  (doseq [k v {:coreBaseDir (str js/__dirname path.sep)
               :clientBaseDir (str (or js/process.env.clientDir (process.cwd))
                                   path.sep)
               :guildConfs Config.guildConfs
               :configuration Config}]
    (o/set client k v)))

(defn- on-ready [client]
  (o/set client :config (assoc
                         (.-config client)
                         :prefix-mention
                         (re-pattern (str "^<@!?" client.user.id ">"))))
  (doseq [f [client.configuration.initialize
             loadFunctions loadProviders
             loadCommands loadCommandInhibitors
             loadMessageMonitors]]
    (await-p (f client)))
  (funcs.i18n/init client)
  (o/set client
         "destroy"
         #(str "You cannot use this within PiethesThcript; use (.exit js/process) instead."))
  (o/set client :ready true))

(defn on-message [msg]
  (when (:ready client)
    (await-p (funcs.run-message-monitors/run client msg))
    (o/set (.-permLevel msg.author)
           (await-p (funcs.permission-level/run client msg.author msg.guild)))
    (o/set (.-guildConf msg) (.get Config msg.guild))
    (funcs.i18n/use (o/getValueByKeys msg "guildConf" "lang"))
    (if (funcs.handle-message/run client msg)
      (let [command (funcs.parse-command/run client msg)]
        (funcs.handle-command/run client msg command)))))

(defn start
  [config]
  (if-not (map? config)
    (throw (js/TypeError. "Configuration for PiethesThcript must be a map.")))
  (let [client (Client. Discord (:client-options config))]
    (o/set client :config config)

    (extend-client! client)

    (await-p (load-events client))

    (.once client "ready" (partial on-ready client))

    (.on client "error" #(funcs.log/run % "error"))
    (.on client "warn" #(funcs.log/run % "warning"))
    (.on client "disconnect" #(funcs.log/run % "error"))

    (.on client "message" on-message)

    (.login client (:bot-token client.config))
    client))

(.on js/process
     "unhandledRejection"
     (fn [err]
       ;; Empty JS objects are not falsy in CLJS
       (if (o/isEmpty err)
         (.error js/console (str "Uncaught Promise Error: \n" (or err.stack err))))))
