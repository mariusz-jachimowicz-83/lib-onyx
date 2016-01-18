(ns lib-onyx.plugins.core-async
  (:require [clojure.core.async :refer [chan sliding-buffer >!!]]
            [lib-onyx.job.utils :refer [find-task-by-key add-to-job instrument-plugin-lifecycles
                                        unit-lens catalog-entrys-by-name]]
            [traversy.lens :refer :all :rename {update lupdate}]))

(defonce channels (atom {}))

(defn- get-channel [id size]
  (or (get @channels id)
      (let [ch (chan size)]
        (swap! channels assoc id ch)
        ch)))

(defn get-input-channel
  ([id] (get-input-channel id nil))
  ([id size]
   (get-channel id size)))

(defn get-output-channel
  ([id] (get-output-channel id nil))
  ([id size]
   (get-channel id size)))

(defn inject-in-ch
  [_ lifecycle]
  {:core.async/chan (get-input-channel (:core.async/id lifecycle)
                                       (:core.async/size lifecycle))})
(defn inject-out-ch
  [_ lifecycle]
  {:core.async/chan (get-output-channel (:core.async/id lifecycle)
                                        (:core.async/size lifecycle))})
(def in-calls
  {:lifecycle/before-task-start inject-in-ch})
(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(defn get-core-async-channels
  "This takes a job and returns (by catalog name) input and output channels
  by their corresponding references. Will not handle multiple input and output channels.

  {:read-lines (chan...)
   :write-lines (chan...)}"
  [job]
  (let [chan-lifecycles (view job (*> (in [:lifecycles])
                                      (only :core.async/id)))]
    (reduce (fn [acc lf]
              (assoc acc (:lifecycle/task lf) (get-output-channel (:core.async/id lf)))) {} chan-lifecycles)))

(defn add-core-async-input
  "Instrument a task with serializeable references to core.async channels
   in the form of UUID's.
   Will have a reference to a channel contained in the channels atom above
   added under core.async/id. The corrosponding channel can be looked up
   manually by passing it's reference directly to get-channel or by using
   get-core-async-channels."
  ([job task] (add-core-async-input job task 1000))
  ([job task chan-size]
   (if-let [entry (-> job (view-single (catalog-entrys-by-name task)))]
     (update-in job [:lifecycles] into [{:lifecycle/task task
                                         :lifecycle/calls :onyx.plugin.core-async/reader-calls}
                                        {:lifecycle/task task
                                         :lifecycle/calls ::in-calls
                                         :core.async/id   (java.util.UUID/randomUUID)
                                         :core.async/size chan-size}]))))

(defn add-core-async-output
  "Instrument a task with serializeable references to core.async channels
   in the form of UUID's.
   Will have a reference to a channel contained in the channels atom above
   added under core.async/id. The corrosponding channel can be looked up
   manually by passing it's reference directly to get-channel or by using
   get-core-async-channels."
  ([job task] (add-core-async-output job task 1000))
  ([job task chan-size]
   (if-let [entry (-> job (view-single (catalog-entrys-by-name task)))]
     (update-in job [:lifecycles] into [{:lifecycle/task task
                                         :lifecycle/calls :onyx.plugin.core-async/reader-calls}
                                        {:lifecycle/task task
                                         :lifecycle/calls ::out-calls
                                         :core.async/id   (java.util.UUID/randomUUID)
                                         :core.async/size (inc chan-size)}]))))
