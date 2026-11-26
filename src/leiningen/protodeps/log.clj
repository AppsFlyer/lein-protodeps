(ns leiningen.protodeps.log
  "Console output shared by the plugin namespaces.")

(defn print-err [& s]
  (binding [*out* *err*]
    (apply println s)))

(defn print-warning [& s]
  (apply print-err "protodeps: WARNING:" s))
