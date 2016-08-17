(ns iwaswhere-web.ui.charts.word-cloud)

;;; WordCloud element (implemented externally in JavaScript)
(def cloud-elem (.getElementById js/document "word-cloud"))
(def w (aget cloud-elem "offsetWidth"))
(def h (* w 0.7))

(defn cloud-chart-state-fn
  "Return clean initial component state atom."
  [put-fn]
  (let [on-click #(put-fn [:cmd/append-search-text %])
        word-cloud (.WordCloud js/BirdWatch w h 250 on-click cloud-elem)]
    {:state (atom {:word-cloud word-cloud})}))

(defn wordcounts-handler
  "Handle incoming messages: process / add to application state."
  [{:keys [current-state msg-payload]}]
  (let [wordcounts (mapv (fn [[k v]] {:key k :value v}) msg-payload)]
    (prn wordcounts)
    (dotimes [_ 10]
      (.redraw (:word-cloud current-state) (clj->js wordcounts)))))

(defn cmp-map
  [cmp-id]
  {:cmp-id      cmp-id
   :state-fn    cloud-chart-state-fn
   :handler-map {:stats/wordcounts wordcounts-handler}})
