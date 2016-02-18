(ns kabel.platform
  (:require [kabel.platform-log :refer [debug info warn error]]
            [cognitect.transit :as transit]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [goog.net.WebSocket]
            [goog.Uri]
            [goog.events :as events]
            [cljs.core.async :as async :refer (take! put! close! chan)])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))





(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-opts is ignored on cljs for now, use the
  platform-wide reader setup."
  ([url err-ch]
   (client-connect! url err-ch (atom {}) (atom {})))
  ([url err-ch read-handlers write-handlers]
   (let [host (.getDomain (goog.Uri. url))
         channel (goog.net.WebSocket. false)
         in (chan)
         out (chan)
         opener (chan)]
     (info "CLIENT-CONNECT" url)
     (doto channel
       (events/listen goog.net.WebSocket.EventType.MESSAGE
                      (fn [evt]
                        (try
                          (let [reader (transit/reader :json {:handlers ;; remove if uuid problem is gone
                                                              {"u" (fn [v] (cljs.core/uuid v))
                                                               "incognito" (incognito-read-handler read-handlers)}})
                                fr (js/FileReader.)]
                            (set! (.-onload fr) #(put! in
                                                       (assoc
                                                        (transit/read
                                                         reader
                                                         (js/String. (.. % -target -result)))
                                                        :peer host)))
                            (.readAsText fr (.-message evt)))
                          (catch js/Error e
                            (error "Cannot read transit msg:" e)
                            (put! err-ch e)))))
       (events/listen goog.net.WebSocket.EventType.CLOSED
                      (fn [evt]
                        (let [e (ex-info "Connection closed!" {:event evt})]
                          (close! in)
                          (put! err-ch e)
                          (try (put! opener e) (catch js/Object e))
                          (.close channel)
                          (close! opener))))
       (events/listen goog.net.WebSocket.EventType.OPENED
                      (fn [evt] (put! opener [in out]) (close! opener)))
       (events/listen goog.net.WebSocket.EventType.ERROR
                      (fn [evt]
                        (let [e (ex-info "Connection error!" {:event evt})]
                          (error "WebSocket error:" evt)
                          (try (put! opener e) (catch js/Object e))
                          (put! err-ch e)
                          (close! opener))))
       (try
         (.open channel url) ;; throws on connection failure? doesn't catch?
         (catch js/Object e
           (let [e (ex-info  "Connection failed!" {:event e})]
             (put! err-ch e)
             (put! opener e)
             (close! opener)))))
     ((fn sender []
        (take! out
               (fn [m]
                 ;; TODO close if nil
                 (when m
                   (try
                     (let [i-write-handler (incognito-write-handler write-handlers)
                           writer (transit/writer
                                   :json
                                   {:handlers {"default" i-write-handler}})]
                       (.send channel (js/Blob. #js [(transit/write writer m)])))
                     (catch js/Error e
                       (error "Cannot send transit msg: " e)
                       (put! err-ch e)))

                   (sender))))))
     opener)))


(comment
  (client-connect! "ws://127.0.0.1:9090"))


;; fire up repl
#_(do
    (ns dev)
    (def repl-env (reset! cemerick.austin.repls/browser-repl-env
                         (cemerick.austin/repl-env)))
    (cemerick.austin.repls/cljs-repl repl-env))
