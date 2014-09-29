(ns boot.task.built-in
  (:require
   [clojure.java.io          :as io]
   [clojure.set              :as set]
   [clojure.pprint           :as pprint]
   [clojure.string           :as string]
   [boot.pod                 :as pod]
   [boot.file                :as file]
   [boot.core                :as core]
   [boot.main                :as main]
   [boot.util                :as util]
   [boot.gitignore           :as git]
   [boot.task-helpers        :as helpers]
   [boot.from.table.core     :as table])
  (:import
   [java.io File]
   [java.util Arrays]
   [javax.tools ToolProvider DiagnosticCollector Diagnostic$Kind]
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(core/deftask help
  "Print usage info and list available tasks."
  []
  (core/with-pre-wrap
    (let [tasks (#'helpers/available-tasks 'boot.user)
          opts  (->> main/cli-opts (mapv (fn [[x y z]] ["" (str x " " y) z])))
          envs  [["" "BOOT_HOME"            "Directory where boot stores global state (~/.boot)."]
                 ["" "BOOT_LOCAL_REPO"      "The local Maven repo path (~/.m2/repository)."]
                 ["" "BOOT_JVM_OPTIONS"     "Specify JVM options (Unix/Linux/OSX only)."]
                 ["" "BOOT_CHANNEL"         "Set to 'DEV' to update boot via the testing branch."]
                 ["" "BOOT_VERSION"         "Specify the version of boot core to use."]
                 ["" "BOOT_CLOJURE_VERSION" "The version of Clojure boot will provide (1.6.0)."]]
          files [["" "./.boot"              "Directory where boot stores local state."]
                 ["" "./build.boot"         "The build script for this project."]
                 ["" "./boot.properties"    "Specify boot and clj versions for this project."]
                 ["" "$HOME/.profile.boot"  "A script to run before running the build script."]]
          br    #(conj % ["" "" ""])]
      (boot.App/usage)
      (printf "\n%s\n"
        (-> [["" ""] ["Usage:" "boot OPTS <task> TASK_OPTS <task> TASK_OPTS ..."]]
          (table/table :style :none)
          with-out-str))
      (printf "%s\nDo `boot <task> -h` to see usage info and TASK_OPTS for <task>.\n"
        (-> [["" "" ""]]
          (into (#'helpers/set-title opts "OPTS:")) (br)
          (into (#'helpers/set-title (#'helpers/tasks-table tasks) "Tasks:")) (br)
          (into (#'helpers/set-title envs "Env:")) (br)
          (into (#'helpers/set-title files "Files:"))
          (table/table :style :none)
          with-out-str)))))

(core/deftask speak
  "Audible notifications during build.

  Default themes: system (the default), ordinance, and woodblock. New themes
  can be included via jar dependency with the sound files as resources:

    boot
    └── notify
        ├── <theme-name>_failure.mp3
        ├── <theme-name>_success.mp3
        └── <theme-name>_warning.mp3

  Sound files specified individually take precedence over theme sounds."

  [t theme NAME   str "The notification sound theme."
   s success FILE str "The sound file to play when the build is successful."
   w warning FILE str "The sound file to play when there are warnings reported."
   f failure FILE str "The sound file to play when the build fails."]

  (let [tmp        (core/mktmpdir! ::hear-tmp)
        resource   #(vector %2 (format "boot/notify/%s_%s.mp3" %1 %2))
        resources  #(map resource (repeat %) ["success" "warning" "failure"])
        themefiles (into {}
                     (let [rs (when theme (resources theme))]
                       (when (and (seq rs) (every? (comp io/resource second) rs))
                         (for [[x r] rs]
                           (let [f (io/file tmp (.getName (io/file r)))]
                             (pod/copy-resource r f)
                             [(keyword x) (.getPath f)])))))
        success    (or success (:success themefiles))
        warning    (or warning (:warning themefiles))
        failure    (or failure (:failure themefiles))]
    (fn [continue]
      (fn [event]
        (try
          (util/with-let [ret (continue event)]
            (pod/call-worker
              (if (zero? @core/*warnings*)
                `(boot.notify/success! ~theme ~success)
                `(boot.notify/warning! ~theme ~(deref core/*warnings*) ~warning))))
          (catch Throwable t
            (pod/call-worker
              `(boot.notify/failure! ~theme ~failure))
            (throw t)))))))

(core/deftask show
  "Print project/build info (e.g. dependency graph, etc)."

  [d deps  bool "Print project dependency graph."
   e env   bool "Print the boot env map."
   E event bool "Print the build event data."]

  (core/with-pre-wrap
    (when deps  (print (pod/call-worker `(boot.aether/dep-tree ~(core/get-env)))))
    (when env   (println (pr-str (core/get-env))))
    (when event (println (pr-str core/*event*)))))

(core/deftask wait
  "Wait before calling the next handler.

  Waits forever if the --time option is not specified."

  [t time MSEC int "The interval in milliseconds."]

  (core/with-pre-wrap
    (if (zero? (or time 0)) @(promise) (Thread/sleep time))))

(core/deftask watch
  "Call the next handler whenever source files change.

  Debouncing time is 10ms by default."

  [d debounce MSEC long "The time to wait (millisec) for filesystem to settle down."]

  (pod/require-in-pod @pod/worker-pod "boot.watcher")
  (let [q        (LinkedBlockingQueue.)
        srcdirs  (->> (core/get-env :src-paths) (remove core/tmpfile?))
        watchers (map file/make-watcher srcdirs)
        paths    (into-array String srcdirs)
        k        (.invoke @pod/worker-pod "boot.watcher/make-watcher" q paths)
        ign?     (git/make-gitignore-matcher (core/get-env :src-paths))
        ]
    (fn [continue]
      (fn [event]
        (util/info "Starting file watcher (CTRL-C to quit)...\n")
        (loop [ret (util/guard [(.take q)])]
          (when ret
            (if-let [more (.poll q (or debounce 10) TimeUnit/MILLISECONDS)]
              (recur (conj ret more))
              (let [start   (System/currentTimeMillis)
                    etime   #(- (System/currentTimeMillis) start)
                    changed (->> (map #(%) watchers)
                              (reduce (partial merge-with set/union))
                              :time (remove ign?) set)]
                (when-not (empty? changed)
                  (-> event core/prep-build! (assoc ::watch changed) continue)
                  (util/info "Elapsed time: %.3f sec\n\n" (float (/ (etime) 1000))))
                (recur (util/guard [(.take q)]))))))
        (.invoke @pod/worker-pod "boot.watcher/stop-watcher" k)))))

(core/deftask repl
  "Start a REPL session for the current project.

  If no bind/host is specified the REPL server will listen on 0.0.0.0 and the
  client will connect to 127.0.0.1.

  If no port is specified the server will choose a random one and the client
  will read the .nrepl-port file and use that.

  The #'boot.repl-server/*default-middleware* dynamic var holds a vector of the
  default REPL middleware to be included. You may modify this in your build.boot
  file by calling set! or rebinding the var."

  [s server         bool   "Start REPL server only."
   c client         bool   "Start REPL client only."
   C no-color       bool   "Disable ANSI color output in client."
   b bind ADDR      str    "The address server listens on."
   H host HOST      str    "The host client connects to."
   p port PORT      int    "The port to listen on and/or connect to."
   n init-ns NS     str    "The initial REPL namespace."
   m middleware SYM [code] "The REPL middleware vector."]

  (let [srv-opts (select-keys *opts* [:bind :port :init-ns :middleware])
        cli-opts (-> *opts*
                   (select-keys [:host :port :history])
                   (assoc :color (not no-color)))]
    (core/with-pre-wrap
      (when (or server (not client))
        (future
          (try (require 'clojure.tools.nrepl.server)
               (catch Throwable _
                 (pod/add-dependencies
                   (assoc (core/get-env)
                     :dependencies '[[org.clojure/tools.nrepl "0.2.4"]]))))
          (require 'boot.repl-server)
          ((resolve 'boot.repl-server/start-server) srv-opts)))
      (when (or client (not server))
        (pod/call-worker
          `(boot.repl-client/client ~cli-opts))))))

(core/deftask pom
  "Create project pom.xml file.

  The project and version must be specified to make a pom.xml."

  [p project SYM      sym      "The project id (eg. foo/bar)."
   v version VER      str      "The project version."
   d description DESC str      "The project description."
   u url URL          str      "The project homepage url."
   l license KEY=VAL  {kw str} "The project license map (KEY in name, url)."
   s scm KEY=VAL      {kw str} "The project scm map (KEY in url, tag)."]

  (let [tgt  (core/mktgtdir!)
        opts (assoc *opts* :dependencies (:dependencies (core/get-env)))]
    (core/with-pre-wrap
      (when-not (and project version)
        (throw (Exception. "need project and version to create pom.xml")))
      (let [[gid aid] (util/extract-ids project)
            pomdir    (io/file tgt "META-INF" "maven" gid aid)
            xmlfile   (io/file pomdir "pom.xml")
            propfile  (io/file pomdir "pom.properties")]
        (when-not (and (.exists xmlfile) (.exists propfile))
          (util/info "Writing %s and %s...\n" (.getName xmlfile) (.getName propfile))
          (pod/call-worker
            `(boot.pom/spit-pom! ~(.getPath xmlfile) ~(.getPath propfile) ~opts)))))))

(core/deftask add-dir
  "Add files in resource directories to fileset.

  The include and exclude options specify sets of regular expressions (strings)
  that will be used to filter the source files. If no filters are specified then
  all files are added to the fileset."

  [d dirs PATH     #{str} "The set of resource directories."
   i include REGEX #{str} "The set of regexes that paths must match."
   x exclude REGEX #{str} "The set of regexes that paths must not match."]

  (let [tgt  (core/mktgtdir!)
        ign? (git/make-gitignore-matcher (core/get-env :src-paths))]
    (core/with-pre-wrap
      (when (seq dirs)
        (util/info "Adding resource files...\n")
        (binding [file/*ignore*  ign?
                  file/*include* (mapv re-pattern include)
                  file/*exclude* (mapv re-pattern exclude)]
          (apply file/sync :time tgt dirs))))))

(core/deftask add-src
  "Add source files to fileset.

  The include and exclude options specify sets of regular expressions (strings)
  that will be used to filter the source files. If no filters are specified then
  all files are added to the fileset."

  [i include REGEX #{str} "The set of regexes that paths must match."
   x exclude REGEX #{str} "The set of regexes that paths must not match."]

  (let [dirs (remove core/tmpfile? (core/get-env :src-paths))]
    (add-dir :dirs dirs :include include :exclude exclude)))

(core/deftask map-fileset
  "Transform current fileset with given mapping fn.

  The mapping function will be called with the relative path of each file in the
  current fileset. The function should return the relative path desired for that
  file in the result fileset. If the function returns nil then that file will be
  removed from the result fileset."
  
  [f map-fn CODE code "The mapping function."]
  
  (let [tgt  (core/mktgtdir!)]
    (core/with-pre-wrap
      (when map-fn
        (doseq [f (core/tgt-files) :let [p (core/relative-path f)]]
          (when-let [p (map-fn p)]
            (file/copy-with-lastmod f (io/file tgt p)))
          (core/consume-file! f))))))

(core/deftask uber
  "Add jar entries from dependencies to fileset.

  By default, entries from dependencies with the following scopes will be copied
  to the fileset: compile, runtime, and provided. The exclude option may be used
  to exclude dependencies with the given scope(s).

  The include and exclude options specify sets of regular expressions (strings)
  that will be used to filter the entries. If no filters are specified then all
  entries are added to the fileset."

  [S exclude-scope SCOPE #{str} "The set of excluded scopes."
   i include REGEX       #{str} "The set of regexes that paths must match."
   x exclude REGEX       #{str} "The set of regexes that paths must not match."
   ]

  (let [tgt        (core/mktgtdir!)
        dfl-scopes #{"compile" "runtime" "provided"}
        scopes     (set/difference dfl-scopes exclude-scope)
        include    (map re-pattern include)
        exclude    (map re-pattern exclude)]
    (core/with-pre-wrap
      (let [scope? #(contains? scopes (:scope (util/dep-as-map %)))
            urls   (-> (core/get-env)
                     (update-in [:dependencies] (partial filter scope?))
                     pod/jar-entries-in-dep-order)]
        (util/info "Adding uberjar entries...\n")
        (doseq [[relpath url-str] urls :let [f (io/file relpath)]]
          (when (file/keep-filters? include exclude f)
            (let [segs    (file/split-path relpath)
                  outfile (apply io/file tgt segs)]
              (when-not (or (.exists outfile) (= "META-INF" (first segs)))
                (pod/copy-url url-str outfile)))))))))

(core/deftask web
  "Create project web.xml file.

  The --serve option is required. The others are optional."

  [s serve SYM        sym "The 'serve' callback function."
   c create SYM       sym "The 'create' callback function."
   d destroy SYM      sym "The 'destroy' callback function."]

  (let [tgt      (core/mktgtdir!)
        xmlfile  (io/file tgt "WEB-INF" "web.xml")
        implp    'tailrecursion/clojure-adapter-servlet
        implv    "0.1.0-SNAPSHOT"]
    (core/with-pre-wrap
      (when-not (.exists xmlfile)
        (-> (and (symbol? serve) (namespace serve))
          (assert "no serve function specified"))
        (util/info "Adding servlet impl...\n")
        (pod/copy-dependency-jar-entries
          (core/get-env) tgt [implp implv] #"^tailrecursion/.*\.(class|clj)$")
        (util/info "Writing %s...\n" (.getName xmlfile))
        (pod/call-worker
          `(boot.web/spit-web! ~(.getPath xmlfile) ~serve ~create ~destroy))))))

(core/deftask aot
  "Perform AOT compilation of Clojure namespaces."
  
  [a all          bool   "Compile all namespaces."
   n namespace NS #{sym} "The set of namespaces to compile."]
  
  (let [tgt (core/mktgtdir!)]
    (core/with-pre-wrap
      (let [nses (->> (core/src-files)
                   (map core/relative-path)
                   (filter #(.endsWith % ".clj"))
                   (map util/path->ns)
                   (filter (if all (constantly true) #(contains? namespace %))))]
        (binding [*compile-path* (.getPath tgt)]
          (doseq [ns nses]
            (util/info "Compiling %s...\n" ns)
            (compile ns)))))))

(core/deftask javac
  "Compile java sources."
  []
  (let [tgt (core/mktgtdir!)]
    (core/with-pre-wrap
      (let [throw?    (atom nil)
            diag-coll (DiagnosticCollector.)
            compiler  (ToolProvider/getSystemJavaCompiler)
            file-mgr  (.getStandardFileManager compiler diag-coll nil nil)
            opts      (->> ["-d" (.getPath tgt)] (into-array String) Arrays/asList)
            handler   {Diagnostic$Kind/ERROR util/fail
                       Diagnostic$Kind/WARNING util/warn
                       Diagnostic$Kind/MANDATORY_WARNING util/warn}
            srcs      (some->> (core/src-files)
                        (core/by-ext [".java"])
                        seq
                        (into-array File)
                        Arrays/asList
                        (.getJavaFileObjectsFromFiles file-mgr))]
        (when srcs
          (util/info "Compiling Java classes...\n")
          (-> compiler (.getTask *err* file-mgr diag-coll opts nil srcs) .call)
          (doseq [d (.getDiagnostics diag-coll) :let [k (.getKind d)]]
            (when (= Diagnostic$Kind/ERROR k) (reset! throw? true))
            ((handler k util/info)
              "%s: %s, line %d: %s\n"
              (.toString k)
              (.. d getSource getName)
              (.getLineNumber d)
              (.getMessage d nil)))
          (.close file-mgr)
          (when @throw? (throw (Exception. "java compiler error"))))))))

(core/deftask jar
  "Build a jar file for the project.

  The include and exclude options specify sets of regular expressions (strings)
  that will be used to filter the entries. If no filters are specified then all
  entries are added to the jar file.
"

  [f file PATH        str       "The target jar file."
   M manifest KEY=VAL {str str} "The jar manifest map."
   m main MAIN        sym       "The namespace containing the -main function."
   i include REGEX       #{str} "The set of regexes that paths must match."
   x exclude REGEX       #{str} "The set of regexes that paths must not match."]

  (let [tgt (core/mktgtdir!)]
    (core/with-pre-wrap
      (let [pomprop (->> (core/tgt-files) (core/by-name ["pom.properties"]) first)
            [aid v] (some->> pomprop pod/pom-properties-map ((juxt :artifact-id :version)))
            jarname (or file (and aid v (str aid "-" v ".jar")) "project.jar")
            jarfile (io/file tgt jarname)]
        (when-not (.exists jarfile)
          (let [index (->> (core/tgt-files)
                        (filter (partial file/keep-filters? include exclude))
                        (map (juxt core/relative-path (memfn getPath))))]
            (util/info "Writing %s...\n" (.getName jarfile))
            (pod/call-worker
              `(boot.jar/spit-jar! ~(.getPath jarfile) ~index ~manifest ~main))
            (doseq [[_ f] index] (core/consume-file! (io/file f)))))))))

(core/deftask war
  "Create war file for web deployment.

  The include and exclude options specify sets of regular expressions (strings)
  that will be used to filter the entries. If no filters are specified then all
  entries are added to the war file."

  [f file PATH     str    "The target war file."
   i include REGEX #{str} "The set of regexes that paths must match."
   x exclude REGEX #{str} "The set of regexes that paths must not match."]

  (let [tgt (core/mktgtdir!)]
    (core/with-pre-wrap
      (let [warname (or file "project.war")
            warfile (io/file tgt warname)]
        (when-not (.exists warfile)
          (let [->war #(let [r  (core/relative-path %)
                             r' (file/split-path r)]
                         (if (contains? #{"META-INF" "WEB-INF"} (first r'))
                           r
                           (.getPath (apply io/file "WEB-INF" "classes" r'))))
                index (->> (core/tgt-files)
                        (filter (partial file/keep-filters? include exclude))
                        (map (juxt ->war (memfn getPath))))]
            (util/info "Writing %s...\n" (.getName warfile))
            (pod/call-worker
              `(boot.jar/spit-jar! ~(.getPath warfile) ~index {} nil))
            (doseq [[_ f] index] (core/consume-file! (io/file f)))))))))

(core/deftask install
  "Install project jar to local Maven repository.

  The file option allows installation of arbitrary jar files. If no file option
  is given then any jar artifacts created during the build will be installed.

  Note that installation requires the jar to contain a pom.xml file."

  [f file PATH str "The jar file to install."]

  (core/with-pre-wrap
    (let [jarfiles (or (and file [(io/file file)])
                     (->> (core/tgt-files) (core/by-ext [".jar"])))]
      (when-not (seq jarfiles) (throw (Exception. "can't find jar file")))
      (doseq [jarfile jarfiles]
        (util/info "Installing %s...\n" (.getName jarfile))
        (pod/call-worker
          `(boot.aether/install ~(core/get-env) ~(.getPath jarfile)))))))

(core/deftask push
  "Deploy jar file to a Maven repository.

  Both the file and repo options are required. The jar file must contain a
  pom.xml entry."

  [f file PATH  str "The jar file to deploy."
   r repo ALIAS str "The alias of the deploy repository."]

  (core/with-pre-wrap
    (let [f (io/file file)
          r (-> (->> (core/get-env :repositories) (into {})) (get repo))]
      (when-not (and r (.exists f))
        (throw (Exception. "missing jar file or repo alias option")))
      (util/info "Deploying %s...\n" (.getName f))
      (pod/call-worker
        `(boot.aether/deploy ~(core/get-env) ~[repo r] ~(.getPath f))))))
