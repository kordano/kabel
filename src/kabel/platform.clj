(ns kabel.platform
  "Platform specific io operations."
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kabel.platform-log :refer [debug info warn error]]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [full.async :refer [<? <?? go-try go-loop-try>]]
            [clojure.core.async :as async
             :refer [>! timeout chan alt!]]
            [org.httpkit.server :refer :all]
            [http.async.client :as cli]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [com.cognitect.transit.impl WriteHandlers$MapWriteHandler]))


(defn now [] (java.util.Date.))

(def singleton-http-client (cli/create-client))

(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
  Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-handlers and write-handlers are atoms
  according to incognito."
  ([url err-ch]
   (client-connect! url err-ch (atom {}) (atom {}) singleton-http-client))
  ([url err-ch read-handlers write-handlers http-client]
   (let [host (.getHost (java.net.URL. (str/replace url #"^ws" "http")))
         in (chan)
         out (chan)
         opener (chan)]
     (try
       (cli/websocket http-client url
                      :open (fn [ws]
                              (info "ws-opened" ws)
                              (go-loop-try> err-ch [m (<? out)]
                                            (when m
                                              (debug "client sending msg to:" url (:type m))
                                              (with-open [baos (ByteArrayOutputStream.)]
                                                (let [writer (transit/writer baos :json
                                                                             {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                                  (transit/write writer m ))
                                                (cli/send ws :byte (.toByteArray baos)))
                                              (recur (<? out))))
                              (async/put! opener [in out])
                              (async/close! opener))
                      :byte (fn [ws ^bytes data]
                              (debug "received byte message")
                              (with-open [bais (ByteArrayInputStream. data)]
                                (let [reader
                                      (transit/reader bais :json
                                                      {:handlers {"incognito" (incognito-read-handler read-handlers)}})
                                      m (transit/read reader)]
                                  (debug "client received transit blob from:" url (:type m))
                                  (async/put! in (with-meta m {:host host})))))
                      :close (fn [ws code reason]
                               (info "closing" ws code reason)
                               (async/close! in)
                               (async/close! out))
                      :error (fn [ws err] (error "ws-error" url err)
                               (async/put! opener (ex-info "ws-error"
                                                           {:type :websocket-connection-error
                                                            :url url
                                                            :error err}))
                               (async/close! opener)))
       (catch Exception e
         (error "client-connect error:" url e)
         (async/put! opener (ex-info "client-connect error"
                                     {:type :websocket-connection-error
                                      :url url
                                      :error e}))
         (async/close! in)
         (async/close! opener)))
     opener)))


(defn create-http-kit-handler!
  "Creates a server handler described by url, e.g. wss://myhost:8443/replikativ/ws.
  Returns a map to run a peer with a platform specific server handler
  under :handler.  read-handlers and write-handlers are atoms
  according to incognito."
  ([url err-ch]
   (create-http-kit-handler! url err-ch (atom {}) (atom {})))
  ([url err-ch read-handlers write-handlers]
   (let [channel-hub (atom {})
         conns (chan)
         handler (fn [request]
                   (let [client-id (gensym)
                         in (chan)
                         out (chan)]
                     (async/put! conns [in out])
                     (with-channel request channel
                       (swap! channel-hub assoc channel request)
                       (go-loop-try> err-ch
                                     [m (<? out)]
                                     (when m
                                       (if (@channel-hub channel)
                                         (do
                                           (with-open [baos (ByteArrayOutputStream.)]
                                             (let [writer (transit/writer baos :json
                                                                          {:handlers {java.util.Map (incognito-write-handler write-handlers)}})]
                                               (debug "server sending msg:" url (:type m))
                                               (transit/write writer m)
                                               (debug "server sent transit msg"))
                                             (send! channel ^bytes (.toByteArray baos))))
                                         (warn "dropping msg because of closed channel: " url (pr-str m)))
                                       (recur (<? out))))
                       (on-close channel (fn [status]
                                           (info "channel closed:" status)
                                           (swap! channel-hub dissoc channel)
                                           (async/close! in)))
                       (on-receive channel (fn [data]
                                             (let [blob data
                                                   host (:remote-addr request)]
                                               (debug "received byte message")
                                               (with-open [bais (ByteArrayInputStream. blob)]
                                                 (let [reader
                                                       (transit/reader bais :json
                                                                       {:handlers {"incognito" (incognito-read-handler read-handlers)}})
                                                       m (transit/read reader)]
                                                   (debug "server received transit blob from:"
                                                          url (apply str (take 100 (str m))))
                                                   (async/put! in (with-meta m {:host host}))))))))))]
     {:new-conns conns
      :channel-hub channel-hub
      :url url
      :handler handler})))




(defn start [peer]
  (when-let [handler (-> @peer :volatile :handler)]
    (println "starting" (:name @peer))
    (swap! peer assoc-in [:volatile :server]
           (run-server handler {:port (->> (-> @peer :volatile :url)
                                           (re-seq #":(\d+)")
                                           first
                                           second
                                           read-string)
                                :max-body (* 512 1024 1024)}))))


(defn stop [peer]
  (when-let [stop-fn (get-in @peer [:volatile :server])]
    (stop-fn :timeout 100))
  (<?? (timeout 200))
  (when-let [hub (get-in @peer [:volatile :channel-hub])]
    (reset! hub {}))
  (when-let [in (-> @peer :volatile :chans first)]
    (async/close! in)))


(comment
  (defrecord Foo [a b])

  {:handlers {java.util.Map irecord-write-handler}}

  (def out (ByteArrayOutputStream. 4096))
  (def writer (transit/writer out :json {:handlers
                                         ;; add ground type for records
                                         {java.util.Map
                                          (proxy [WriteHandlers$MapWriteHandler] []
                                            (tag [o] (if (isa? (type o) clojure.lang.IRecord)
                                                       "irecord"
                                                       (proxy-super tag o)))
                                            (rep [o] (if (isa? (type o)  clojure.lang.IRecord)
                                                       (assoc (into {} o) :_gnd$tl (pr-str (type o)))
                                                       (proxy-super rep o))))}}))
  (transit/write writer "foo")
  (transit/write writer {:a [1 2]})
  (transit/write writer (Foo. 3 4))


  (require '[clojure.reflect :refer [reflect]]
           '[clojure.pprint :refer [pprint]])

  (import '[com.cognitect.transit.impl WriteHandlers$MapWriteHandler])

  (import '[com.cognitect.transit.impl ReadHandlers$MapReadHandler])

  (pprint (reflect (proxy [WriteHandlers$MapWriteHandler] []
                     (tag [o] (if (isa? clojure.lang.IRecord (type o))
                                "irecord"
                                (proxy-super tag o))))))

  (.tag (proxy [WriteHandlers$MapWriteHandler] []
          (tag [o] (if (isa? clojure.lang.IRecord (type o))
                     "irecord"
                     (proxy-super tag o)))
          (rep [o] (if (isa? clojure.lang.IRecord (type o))
                     (assoc (into {} o) :_gnd$tl (pr-str (type o)))
                     (proxy-super rep o))))
        {:a :b})


  (isa? (type (proxy [com.cognitect.transit.MapReadHandler] []
                (tag [o] (if (isa? clojure.lang.IRecord o)
                           "irecord"
                           (proxy-super tag o)))))
        com.cognitect.transit.MapReadHandler)


  ;; Take a peek at the JSON
  (.toString out)
  ;; => "{\"~#'\":\"foo\"} [\"^ \",\"~:a\",[1,2]]"


  ;; Read data from a stream
  (def in (ByteArrayInputStream. (.toByteArray out)))
  (def reader (transit/reader in :json {:handlers {"irecord"
                                                   (transit/read-handler (fn [rep]
                                                                           (try
                                                                             (if (Class/forName (:_gnd$tl rep))
                                                                               ((let [[_ pre t] (re-find #"(.+)\.([^.]+)" (:_gnd$tl rep))]
                                                                                  (resolve (symbol (str pre "/map->" t)))) (dissoc rep :_gnd$tl)))
                                                                             (catch Exception e
                                                                               (debug "Cannot deserialize record" (:_gnd$tl rep) e)
                                                                               rep))))}}))
  (prn (transit/read reader)) ;; => "foo"
  (prn (transit/read reader)) ;; => {:a [1 2]}

  (let [writer (transit/writer baos :json)]
    (.write baos (byte-array 1 (byte 1)))
    (transit/write writer m))
  )