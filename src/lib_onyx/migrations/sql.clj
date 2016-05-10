(ns lib-onyx.migrations.sql
  (:require [joplin.core :as joplin]
            [schema.core :as s]
            [onyx.schema :as os]
            [taoensso.timbre :refer [debug info warn]]))

(defn get-task-map-lifecycle-config
  "Tries to get a piece of data in the task map or lifecycle"
  [lifecycle event k]
  (or (get-in event [:onyx.core/task-map k])
      (get-in lifecycle [k])
      (throw (Exception. (str k " not specified")))))

(defn no-pending-migrations?
  [event lifecycle]
  (let [joplin-config (get-task-map-lifecycle-config lifecycle event :joplin/config)
        joplin-db-env (get-task-map-lifecycle-config lifecycle event :joplin/environment)]
    (debug "Using joplin environment: " (get-in joplin-config [:environments joplin-db-env]))
    (mapv (fn [env]
            (joplin/migrate-db env))
          (get-in joplin-config [:environments joplin-db-env]))
    (if (every? nil? (mapv (fn [env]
                             (joplin/pending-migrations env))
                           (get-in joplin-config [:environments joplin-db-env])))
      (do (info "Migrations successful")
          true)
      (do (warn "Migrations unsuccessful, retrying")
          false))))

(def joplin-migrations
  {:lifecycle/start-task? no-pending-migrations?})

(def JoplinDatabaseDescriptor
  {:type (s/enum :jdbc :sql :es :zk :dt :cass :dynamo)
   (s/optional-key :url) s/Str
   (s/optional-key :host) s/Str
   (s/optional-key :port) s/Str
   (s/optional-key :cluster) s/Str
   (s/optional-key :migrations-table) s/Str})

(def JoplinConfigSchema
  {:databases {s/Any JoplinDatabaseDescriptor}
   (s/optional-key :migrators) {s/Any s/Str}
   (s/optional-key :seeds) s/Any
   :environments {s/Any [{:db JoplinDatabaseDescriptor
                          :migrator s/Str}]}})

(defn with-joplin-migrations
  "This task bundle modifier will continously try to apply data migrations
  before allowing the task to start. Takes a joplin-config as defined in the
  Joplin docs, and a specific 'environment' key to use.
  https://github.com/juxt/joplin

  This does not apply data seeders.
  "
  ([] (with-joplin-migrations nil nil))
  ([joplin-environment] (with-joplin-migrations joplin-environment nil))
  ([joplin-environment joplin-config]
   (fn [task-definition]
     (let [task-name (get-in task-definition [:task :task-map :onyx/name])]
       (cond-> task-definition
         joplin-config (assoc-in [:task :task-map :joplin/config] joplin-config)
         joplin-environment (assoc-in [:task :task-map :joplin/environment] joplin-environment)
         true (->
               (update-in [:task :lifecycles] conj {:lifecycle/task task-name
                                                    :lifecycle/calls ::joplin-migrations})
               (assoc-in [:schema :task-map :joplin/config] JoplinConfigSchema)
               (assoc-in [:schema :task-map :joplin/environment] s/Keyword)
               (assoc-in [:schema :task-map (os/restricted-ns :joplin)] s/Any)))))))