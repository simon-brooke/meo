(ns iwaswhere-web.graph.stats
  "Get stats from graph."
  (:require [ubergraph.core :as uber]
            [iwaswhere-web.graph.query :as gq]
            [clojure.data.priority-map :as pm]
            [clojure.string :as s]))

(defn get-pomodoro-day-stats
  "Get pomodoro stats for specified day."
  [{:keys [current-state msg-payload msg-meta]}]
  (let [g (:graph current-state)
        date-string (:date-string msg-payload)
        day-nodes (gq/get-nodes-for-day g {:date-string date-string})
        day-nodes-attrs (map #(uber/attrs g %) day-nodes)
        pomodoro-nodes (filter #(= (:entry-type %) :pomodoro) day-nodes-attrs)
        stats {:date-string date-string
               :total       (count pomodoro-nodes)
               :completed   (count (filter #(= (:planned-dur %)
                                               (:completed-time %))
                                           pomodoro-nodes))
               :started     (count (filter #(and (pos? (:completed-time %))
                                                 (< (:completed-time %)
                                                    (:planned-dur %)))
                                           pomodoro-nodes))
               :total-time  (apply + (map :completed-time pomodoro-nodes))}]
    {:emit-msg (with-meta [:stats/pomo-day stats] msg-meta)}))

(defn get-tasks-day-stats
  "Get pomodoro stats for specified day."
  [{:keys [current-state msg-payload msg-meta]}]
  (let [g (:graph current-state)
        date-string (:date-string msg-payload)
        day-nodes (gq/get-nodes-for-day g {:date-string date-string})
        day-nodes-attrs (map #(uber/attrs g %) day-nodes)
        task-nodes (filter #(contains? (:tags %) "#task") day-nodes-attrs)
        done-nodes (filter #(contains? (:tags %) "#done") day-nodes-attrs)
        stats {:date-string date-string
               :tasks-cnt   (count task-nodes)
               :done-cnt    (count done-nodes)}]
    {:emit-msg (with-meta [:stats/tasks-day stats] msg-meta)}))

(defn get-activity-day-stats
  "Get activity stats for specified day."
  [{:keys [current-state msg-payload msg-meta]}]
  (let [g (:graph current-state)
        date-string (:date-string msg-payload)
        day-nodes (gq/get-nodes-for-day g {:date-string date-string})
        day-nodes-attrs (map #(uber/attrs g %) day-nodes)
        weight-nodes (sort-by #(-> % :measurements :weight :value)
                              (filter #(:weight (:measurements %))
                                      day-nodes-attrs))
        activity-nodes (filter :activity day-nodes-attrs)
        activities (map :activity activity-nodes)
        stats {:date-string    date-string
               :total-exercise (apply + (map :duration-m activities))
               :weight         (:weight (:measurements (first weight-nodes)))}]
    {:emit-msg (with-meta [:stats/activity-day stats] msg-meta)}))

(defn count-open-tasks
  [current-state]
  (count (:entries (gq/get-filtered-results
                     current-state
                     {:search-text "#task ~#done ~#backlog"
                      :tags        #{"#task"}
                      :not-tags    #{"#done" "#backlog"}
                      :n           Integer/MAX_VALUE}))))

(defn count-open-tasks-backlog
  [current-state]
  (count (:entries (gq/get-filtered-results
                     current-state
                     {:search-text "#task ~#done #backlog"
                      :tags        #{"#task" "#backlog"}
                      :not-tags    #{"#done"}
                      :n           Integer/MAX_VALUE}))))

(defn count-completed-tasks
  [current-state]
  (count (:entries (gq/get-filtered-results
                     current-state
                     {:search-text "#task #done"
                      :tags        #{"#task" "#done"}
                      :n           Integer/MAX_VALUE}))))

(defn get-basic-stats
  "Generate some very basic stats about the graph size for display in UI."
  [current-state]
  {:entry-count    (count (:sorted-entries current-state))
   :node-count     (count (:node-map (:graph current-state)))
   :edge-count     (count (uber/find-edges (:graph current-state) {}))
   :open-tasks-cnt (count-open-tasks current-state)
   :backlog-cnt    (count-open-tasks-backlog current-state)
   :completed-cnt  (count-completed-tasks current-state)})

(def stop-words
  #{"use" "good" "want" "amp" "just" "now" "like" "til" "new" "get" "one" "i"
    "me" "my" "myself" "we" "us" "our" "ours" "ourselves" "you" "your" "yours"
    "yourself" "yourselves" "he" "him" "his" "himself" "she" "her" "hers"
    "herself" "it" "its" "itself" "they" "them" "their" "theirs" "themselves"
    "what" "which" "who" "whom" "whose" "this" "that" "these" "those" "am" "is"
    "are" "was" "were" "be" "been" "being" "have" "has" "had" "having" "do"
    "does" "did" "doing" "will" "would" "should" "can" "could" "ought" "i'm"
    "you're" "he's" "she's" "it's" "we're" "they're" "i've" "you've" "we've"
    "they've" "i'd" "you'd" "he'd" "she'd" "we'd" "they'd" "i'll" "you'll"
    "he'll" "she'll" "we'll" "they'll" "isn't" "aren't" "wasn't" "weren't"
    "hasn't" "haven't" "hadn't" "doesn't" "don't" "didn't" "won't" "wouldn't"
    "shan't" "shouldn't" "can't" "cannot" "couldn't" "mustn't" "let's" "that's"
    "who's" "what's" "here's" "there's" "when's" "where's" "why's" "how's" "a"
    "an" "the" "and" "but" "if" "or" "because" "as" "until" "while" "of" "at"
    "by" "for" "with" "about" "against" "between" "into" "through" "during"
    "before" "after" "above" "below" "to" "from" "up" "upon" "down" "in" "out"
    "on" "off" "over" "under" "again" "further" "then" "once" "here" "there"
    "when" "where" "why" "how" "all" "any" "both" "each" "few" "more" "most"
    "other" "some" "such" "no" "nor" "not" "only" "own" "same" "so" "than"
    "too" "come" "very" "say" "says" "said" "shall" "via" "htt…" "don" "let"
    "gonna" "rt" "&amp" "http" "must" "see"})

(defn words-in-entry
  "process tweet: split, filter, lower case, replace punctuation, add word"
  [text]
  (when (and text (seq text))
    (->> (s/split text #"[\s—\u3031-\u3035\u0027\u309b\u309c\u30a0\u30fc\uff70]+")
         (filter #(not (re-find #"(@|https?:)" %)))
         (filter #(> (count %) 3))
         (filter #(< (count %) 25))
         (map s/lower-case)
         (map #(s/replace % #"[;:,/‘’…~\-!?\[\]\"<>()\"@.]+" ""))
         (filter (fn [item] (not (contains? stop-words item)))))))

(defn get-wordcount
  "Count the occurrence of words."
  [{:keys [current-state msg-payload msg-meta]}]
  (prn "get-wordcount" msg-payload)
  (let [query (merge msg-payload {:n Integer/MAX_VALUE})
        {:keys [entries-map]} (gq/get-filtered-results current-state query)
        texts (map :md (vals entries-map))
        words (filter identity (flatten (map words-in-entry texts)))
        inc-count (fn [acc w] (update-in acc [w] #(if (number? %) (inc %) 1)))
        word-counts (reduce inc-count (pm/priority-map-by >) words)
        top-250 (into {} (take 500 word-counts))]
    {:emit-msg (with-meta [:stats/wordcounts top-250] msg-meta)}))
