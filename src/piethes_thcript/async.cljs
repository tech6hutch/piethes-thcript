(ns piethes-thcript.async
  (:require [clojure.core.async :as async]))

(defn handle-promised-result! [c result]
  (if (some? result)
    (async/put! c result)
    (async/close! c)))

(defn p->c
  [p]
  (let [c (async/chan)]
    (.then p (partial handle-promised-result! c))
    c))

(defn await-p
  [p]
  (<!! (p->c p)))
