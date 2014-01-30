(ns clj-jgit.porcelain
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-jgit.util :as util]
            [clj-jgit.internal :refer :all]
            [fs.core :as fs])
  (:import [java.io FileNotFoundException File]
           [org.eclipse.jgit.lib RepositoryBuilder]
           [org.eclipse.jgit.api Git InitCommand StatusCommand AddCommand
            ListBranchCommand PullCommand MergeCommand LogCommand
            LsRemoteCommand Status ResetCommand$ResetType
            FetchCommand]
           [org.eclipse.jgit.submodule SubmoduleWalk]
           [com.jcraft.jsch Session JSch]
           [org.eclipse.jgit.transport FetchResult JschConfigSessionFactory
            OpenSshConfig$Host SshSessionFactory]
           [org.eclipse.jgit.util FS]
           [org.eclipse.jgit.merge MergeStrategy]
           [clojure.lang Keyword]
           [java.util List]
           [org.eclipse.jgit.api.errors JGitInternalException]))

(declare log-builder)

(defmulti discover-repo "Discover a Git repository in a path." type)

(defmethod discover-repo File
  [^File file]
  (discover-repo (.getPath file)))

(defmethod discover-repo String
  [^String path]
  (let [with-git (io/as-file (str path "/.git"))
        bare (io/as-file (str path "/refs"))]
    (cond
     (.endsWith path ".git") (io/as-file path)
     (.exists with-git) with-git
     (.exists bare) (io/as-file path))))

(defn load-repo
  "Given a path (either to the parent folder or to the `.git` folder itself), load the Git repository"
  ^org.eclipse.jgit.api.Git [path]
  (if-let [git-dir (discover-repo path)]
    (-> (RepositoryBuilder.)
        (.setGitDir git-dir)
        (.readEnvironment)
        (.findGitDir)
        (.build)
        (Git.))
    (throw
     (FileNotFoundException. (str "The Git repository at '" path "' could not be located.")))))

(defmacro with-repo
  "Binds `repo` to a repository handle"
  [path & body]
  `(let [~'repo (load-repo ~path)
         ~'rev-walk (new-rev-walk ~'repo)]
     ~@body))

(defn git-add
  "The `file-pattern` is either a single file name (exact, not a pattern) or the name of a directory. If a directory is supplied, all files within that directory will be added. If `only-update?` is set to `true`, only files which are already part of the index will have their changes staged (i.e. no previously untracked files will be added to the index)."
  ([^Git repo file-pattern]
     (git-add repo file-pattern false nil))
  ([^Git repo file-pattern only-update?]
     (git-add repo file-pattern only-update? nil))
  ([^Git repo file-pattern only-update? working-tree-iterator]
     (-> repo
         (.add)
         (.addFilepattern file-pattern)
         (.setUpdate only-update?)
         (.setWorkingTreeIterator working-tree-iterator)
         (.call))))

(defn git-branch-list
  "Get a list of branches in the Git repo. Return the default objects generated by the JGit API."
  ([^Git repo]
     (git-branch-list repo :local))
  ([^Git repo opt]
     (let [opt-val {:all org.eclipse.jgit.api.ListBranchCommand$ListMode/ALL
                    :remote org.eclipse.jgit.api.ListBranchCommand$ListMode/REMOTE}
           branches (if (= opt :local)
                      (-> repo
                          (.branchList)
                          (.call))
                      (-> repo
                          (.branchList)
                          (.setListMode (opt opt-val))
                          (.call)))]
       (seq branches))))

(defn git-branch-current*
  [^Git repo]
  (.getFullBranch (.getRepository repo)))

(defn git-branch-current
  "The current branch of the git repo"
  [^Git repo]
  (str/replace (git-branch-current* repo) #"^refs/heads/" ""))

(defn git-branch-attached?
  "Is the current repo on a branch (true) or in a detached HEAD state?"
  [^Git repo]
  (not (nil? (re-find #"^refs/heads/" (git-branch-current* repo)))))

(defn git-branch-create
  "Create a new branch in the Git repository."
  ([^Git repo branch-name]
     (git-branch-create repo branch-name false nil))
  ([^Git repo branch-name force?]
     (git-branch-create repo branch-name force? nil))
  ([^Git repo branch-name force? ^String start-point]
     (if (nil? start-point)
       (-> repo
           (.branchCreate)
           (.setName branch-name)
           (.setForce force?)
           (.call))
       (-> repo
           (.branchCreate)
           (.setName branch-name)
           (.setForce force?)
           (.setStartPoint start-point)
           (.call)))))

(defn git-branch-delete
  ([^Git repo branch-names]
     (git-branch-delete repo branch-names false))
  ([^Git repo branch-names force?]
     (-> repo
         (.branchDelete)
         (.setBranchNames (into-array String branch-names))
         (.setForce force?)
         (.call))))

(defn git-checkout
  ([^Git repo branch-name]
     (git-checkout repo branch-name false false nil))
  ([^Git repo branch-name create-branch?]
     (git-checkout repo branch-name create-branch? false nil))
  ([^Git repo branch-name create-branch? force?]
     (git-checkout repo branch-name create-branch? force? nil))
  ([^Git repo branch-name create-branch? force? ^String start-point]
     (if (nil? start-point)
       (-> repo
           (.checkout)
           (.setName branch-name)
           (.setCreateBranch create-branch?)
           (.setForce force?)
           (.call))
       (-> repo
           (.checkout)
           (.setName branch-name)
           (.setCreateBranch create-branch?)
           (.setForce force?)
           (.setStartPoint start-point)
           (.call)))))

(declare git-cherry-pick)

(defn git-clone
  ([uri]
     (git-clone uri (util/name-from-uri uri) "origin" "master" false))
  ([uri local-dir]
     (git-clone uri local-dir "origin" "master" false))
  ([uri local-dir remote-name]
     (git-clone uri local-dir remote-name "master" false))
  ([uri local-dir remote-name local-branch]
     (git-clone uri local-dir remote-name local-branch false))
  ([uri local-dir remote-name local-branch bare?]
     (-> (Git/cloneRepository)
         (.setURI uri)
         (.setDirectory (io/as-file local-dir))
         (.setRemote remote-name)
         (.setBranch local-branch)
         (.setBare bare?)
         (.call))))

(defn git-clone2
  [uri {:as options
        :keys [path remote-name branch-name bare clone-all-branches]
        :or {path (util/name-from-uri uri)
             remote-name "origin"
             branch-name "master"
             bare false
             clone-all-branches true}}]
  (doto (Git/cloneRepository)
    (.setURI uri)
    (.setDirectory (io/as-file path))
    (.setRemote remote-name)
    (.setBranch branch-name)
    (.setBare bare)
    (.setCloneAllBranches clone-all-branches)
    (.call)))

(declare git-fetch git-merge)

(defn git-clone-full
  "Clone, fetch the master branch and merge its latest commit"
  ([uri]
     (git-clone-full uri (util/name-from-uri uri) "origin" "master" false))
  ([uri local-dir]
     (git-clone-full uri local-dir "origin" "master" false))
  ([uri local-dir remote-name]
     (git-clone-full uri local-dir remote-name "master" false))
  ([uri local-dir remote-name local-branch]
     (git-clone-full uri local-dir remote-name local-branch false))
  ([uri local-dir remote-name local-branch bare?]
     (let [new-repo (-> (Git/cloneRepository)
                        (.setURI uri)
                        (.setDirectory (io/as-file local-dir))
                        (.setRemote remote-name)
                        (.setBranch local-branch)
                        (.setBare bare?)
                        (.call))
           fetch-result ^FetchResult (git-fetch new-repo)
           merge-result (git-merge new-repo
                                   (first (.getAdvertisedRefs fetch-result)))]
       {:repo new-repo,
        :fetch-result fetch-result,
        :merge-result  merge-result})))

(defn git-commit
  "Commit staged changes."
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.call)))
  ([^Git repo message {:keys [author-name author-email]} {:keys [committer-name committer-email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor author-name author-email)
         (.setCommitter committer-name committer-email)
         (.call))))

(defn git-commit-amend
  "Amend previous commit with staged changes."
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAmend true)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setAmend true)
         (.call)))
  ([^Git repo message {:keys [name email]} {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.setAmend true)
         (.call))))


(defn git-add-and-commit
  "This is the `git commit -a...` command"
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAll true)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setAll true)
         (.call)))
  ([^Git repo message {:keys [name email]} {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.setAll true)
         (.call))))

(defn git-fetch
  "Fetch changes from upstream repository."
  (^org.eclipse.jgit.transport.FetchResult [^Git repo]
     (-> repo .fetch .call))
  (^org.eclipse.jgit.transport.FetchResult [^Git repo remote]
     (-> repo
       (.fetch)
       (.setRemote remote)
       (.call)))
  (^org.eclipse.jgit.transport.FetchResult [^Git repo remote & refspecs]
     (let [^FetchCommand cmd (.fetch repo)]
       (.setRefSpecs cmd ^List (map ref-spec refspecs))
       (.setRemote cmd remote)
       (.call cmd))))

(defn git-init
  "Initialize and load a new Git repository"
  ([] (git-init "."))
  ([target-dir]
     (let [comm (InitCommand.)]
       (-> comm
           (.setDirectory (io/as-file target-dir))
           (.call)))))

(defn git-log
  "Return a seq of all commit objects"
  ([^Git repo]
     (seq (-> repo
              (.log)
              (.call))))
  ([^Git repo hash]
     (seq (-> repo
              (.log)
              (.add (resolve-object hash repo))
              (.call))))
  ([^Git repo hash-a hash-b]
     (seq (-> repo
              ^LogCommand (log-builder hash-a hash-b)
              (.call)))))

(defn- log-builder
  "Builds a log command object for a range of commit-ish names"
  ^org.eclipse.jgit.api.LogCommand [^Git repo hash-a hash-b]
  (let [log (.log repo)]
    (if (= hash-a "0000000000000000000000000000000000000000")
      (.add log (resolve-object hash-b repo))
      (.addRange log (resolve-object hash-a repo) (resolve-object hash-b repo)))))

(def merge-strategies
  {:ours MergeStrategy/OURS
   :resolve MergeStrategy/RESOLVE
   :simple-two-way MergeStrategy/SIMPLE_TWO_WAY_IN_CORE
   :theirs MergeStrategy/THEIRS})

(defn git-merge
  "Merge ref in current branch."
  ([^Git repo commit-ref]
     (let [commit-obj (resolve-object commit-ref repo)]
       (-> repo
           (.merge)
           ^MergeCommand (.include commit-obj)
           (.call))))
  ([^Git repo commit-ref ^Keyword strategy]
     (let [commit-obj (resolve-object commit-ref repo)
           strategy-obj ^MergeStrategy (merge-strategies strategy)]
       (-> repo
           (.merge)
           ^MergeCommand (.include commit-obj)
           ^MergeCommand (.setStrategy strategy-obj)
           (.call)))))

(defn git-pull
  "NOT WORKING: Requires work with configuration"
  [^Git repo]
  (-> repo
      (.pull)
      (.call)))

(defn git-push [])
(defn git-rebase [])
(defn git-revert [])
(defn git-rm
  [^Git repo file-pattern]
  (-> repo
      (.rm)
      (.addFilepattern file-pattern)
      (.call)))

(defn git-status
  "Return the status of the Git repository. Opts will return individual aspects of the status, and can be specified as `:added`, `:changed`, `:missing`, `:modified`, `:removed`, or `:untracked`."
  [^Git repo & fields]
  (let [status (.. repo status call)
        status-fns {:added     #(.getAdded ^Status %)
                    :changed   #(.getChanged ^Status %)
                    :missing   #(.getMissing ^Status %)
                    :modified  #(.getModified ^Status %)
                    :removed   #(.getRemoved ^Status %)
                    :untracked #(.getUntracked ^Status %)}]
    (if-not (seq fields)
      (apply merge (for [[k f] status-fns]
                     {k (into #{} (f status))}))
      (apply merge (for [field fields]
                     {field (into #{} ((field status-fns) status))})))))

(defn git-tag [])

(defn git-ls-remote
  ([^Git repo]
     (-> repo .lsRemote .call))
  ([^Git repo remote]
     (-> repo .lsRemote
         (.setRemote remote)
         .call))
  ([^Git repo remote opts]
     (-> repo .lsRemote
         (.setRemote remote)
         (.setHeads (:heads opts false))
         (.setTags (:tags opts false))
         (.call))))

(def reset-modes
  {:hard ResetCommand$ResetType/HARD
   :keep ResetCommand$ResetType/KEEP
   :merge ResetCommand$ResetType/MERGE
   :mixed ResetCommand$ResetType/MIXED
   :soft ResetCommand$ResetType/SOFT})

(defn git-reset
  ([^Git repo ref]
     (git-reset repo ref :mixed))
  ([^Git repo ref mode-sym]
     (-> repo .reset
         (.setRef ref)
         (.setMode ^ResetCommand$ResetType (reset-modes mode-sym))
         (.call))))

(def ^:dynamic *ssh-identity-name*)
(def ^:dynamic *ssh-prvkey*)
(def ^:dynamic *ssh-pubkey*)
(def ^:dynamic *ssh-passphrase* "")
(def ^:dynamic *ssh-identities* [])
(def ^:dynamic *ssh-exclusive-identity* false)
(def ^:dynamic *ssh-session-config* {})

(def jsch-factory
  (proxy [JschConfigSessionFactory] []
    (configure [hc session]
      (let [jsch (.getJSch this hc FS/DETECTED)]
        (doseq [[key val] *ssh-session-config*]
          (.setConfig session key val))
        (when *ssh-exclusive-identity*
          (.removeAllIdentity jsch))
        (when (and *ssh-prvkey* *ssh-pubkey* *ssh-passphrase*)
          (.addIdentity jsch *ssh-identity-name*
                        (.getBytes *ssh-prvkey*)
                        (.getBytes *ssh-pubkey*)
                        (.getBytes *ssh-passphrase*)))
        (doseq [{:keys [name private-key public-key passphrase]
                 :or {passphrase ""
                      name (str "key-" (.hashCode private-key))}} *ssh-identities*]
          (.addIdentity jsch name
                        (.getBytes private-key)
                        (.getBytes public-key)
                        (.getBytes passphrase)))))))

(SshSessionFactory/setInstance jsch-factory)

(defmacro with-identity
  "Creates an identity to use for SSH authentication."
  [{:keys [name private public passphrase options exclusive identities]
    :or {name "jgit-identity"
         passphrase ""
         options {"StrictHostKeyChecking" "no"}
         exclusive false}} & body]
  `(binding [*ssh-identity-name* ~name
             *ssh-prvkey* ~private
             *ssh-pubkey* ~public
             *ssh-identities* ~identities
             *ssh-passphrase* ~passphrase
             *ssh-session-config* ~options
             *ssh-exclusive-identity* ~exclusive]
     ~@body))

(defn submodule-walk
  ([repo]
     (->> (submodule-walk (.getRepository repo) 0)
          (flatten)
          (map #(Git/wrap %))))
  ([repo level]
     (when (< level 3)
       (let [gen (SubmoduleWalk/forIndex repo)
             repos (transient [])]
         (while (.next gen)
           (when-let [subm (.getRepository gen)]
             (conj! repos subm)
             (conj! repos (submodule-walk subm (inc level)))))
         (->> (persistent! repos)
              (flatten))))))

(defn git-submodule-fetch
  [repo]
  (doseq [subm (submodule-walk repo)]
    (git-fetch subm)))

(defn git-submodule-update
  ([repo]
     "Fetch each submodule repo and update them."
     (git-submodule-fetch repo)
     (doseq [subm (submodule-walk repo)]
       (-> subm
           (.submoduleUpdate)
           (.call))))
  ([repo path]
     (git-submodule-fetch repo)
     (doseq [subm (submodule-walk repo)]
       (-> subm
         (.submoduleUpdate)
         (.addPath path)
         (.call)))))

(defn git-submodule-sync
  ([repo]
     (doseq [subm (submodule-walk repo)]
       (-> subm
           (.submoduleSync)
           (.call))))
  ([repo path]
     (doseq [subm (submodule-walk repo)]
       (-> subm
           (.submoduleSync)
           (.addPath path)
           (.call)))))

(defn git-submodule-init
  ([repo]
     (doseq [subm (submodule-walk repo)]
       (-> subm
           (.submoduleInit)
           (.call))))
  ([repo path]
     (doseq [subm (submodule-walk repo)]
       (-> subm
           (.submoduleInit)
           (.addPath path)
           (.call)))))

(defn git-submodule-add
  [repo uri path]
  (-> repo
      (.submoduleAdd)
      (.setURI uri)
      (.setPath path)
      (.call)))

;;
;; Git Stash Commands
;;

(defn git-create-stash
  [^Git repo]
  (-> repo
      .stashCreate
      .call))

(defn git-apply-stash
  ([^Git repo]
     (git-apply-stash repo nil))
  ([^Git repo ^String ref-id]
     (-> repo
         .stashApply
         (.setStashRef ref-id)
         .call)))

(defn git-list-stash
  [^Git repo]
  (-> repo
      .stashList
      .call))

(defn git-drop-stash
  ([^Git repo]
     (-> repo
         .stashDrop
         .call))
  ([^Git repo ^String ref-id]
     (let [stashes (git-list-stash repo)
           target (first (filter #(= ref-id (second %))
                                 (map-indexed #(vector %1 (.getName %2)) stashes)))]
       (when-not (nil? target)
         (-> repo
             .stashDrop
             (.setStashRef (first target))
             .call)))))

(defn git-pop-stash
  ([^Git repo]
     (git-apply-stash repo)
     (git-drop-stash repo))
  ([^Git repo ^String ref-id]
     (git-apply-stash repo ref-id)
     (git-drop-stash repo ref-id)))

(defn git-clean
  "Remove untracked files from the working tree.

  clean-dirs? - true/false - remove untracked directories
  force-dirs? - true/false - force removal of non-empty untracked directories
  paths - set of paths to cleanup
  ignore? - true/false - ignore paths from .gitignore"
  [^Git repo & {:keys [clean-dirs? ignore? paths force-dirs?]
                :or {clean-dirs? false
                     force-dirs? false
                     ignore? true
                     paths #{}}}]
  (letfn [(clean-loop [retries]
            (try
              (-> repo
                  (.clean)
                  (.setCleanDirectories clean-dirs?)
                  (.setIgnore ignore?)
                  (.setPaths paths)
                  (.call))
              (catch JGitInternalException e
                (if-not force-dirs?
                  (throw e)
                  (when-let [dir-path (->> (.getMessage e)
                                           (re-seq #"^Could not delete file (.*)$")
                                           (first)
                                           (last))]
                    (if (retries dir-path)
                      (throw e)
                      (fs/delete-dir dir-path))
                    #(clean-loop (conj retries dir-path)))))))]
    (trampoline clean-loop #{})))
