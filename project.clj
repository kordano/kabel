(defproject io.replikativ/kabel "0.1.0-SNAPSHOT"
  :description "A library for simple wire-like connectivity semantics."
  :url "http://github.com/replikativ/kabel"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.107"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [http-kit "2.1.19"]
                 [http.async.client "0.6.0"]

                 [io.replikativ/incognito "0.2-SNAPSHOT"]

                 [es.topiq/full.async "0.2.8-beta1"]
                 [kordano/full.cljs.async "0.1.3-alpha"]

                 [com.taoensso/timbre "4.0.2"]]

  :plugins [[lein-cljsbuild "1.1.0"]])
