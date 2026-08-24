# Golden-set evaluation

`scripts/run-eval.sh <run-id>` scores retrieval against a fixed corpus and writes
`eval-runs/<run-id>/report.json`. The gate runs in CI on every push
(`GoldenSetRegressionTests`). It uses either models bundled under `target/classes` or the pinned
model/tokenizer pairs automatically provisioned under `models`, so this entire directory can be shared and
evaluated as part of the standalone `mcp-server` folder.

## Shape of the fixture

- `corpus/documents.json` — 40 documents in **8 near-duplicate families** of 5. Within a family the
  documents share most of their prose and differ in one decisive dimension, which is what makes the
  set discriminating: Cloud vs Server connector setup, a 30-day vs 90-day retention policy, and an
  "ACL tag" / "permission label" pair that name genuinely different mechanisms. A corpus of
  topically disjoint documents cannot distinguish a good ranker from a mediocre one — the previous
  10-document fixture scored P@1 0.98 and had no headroom to detect a regression or an improvement.
- `golden-set/search.json` — 160 queries, 4 per document, with **graded relevance**:
  `grades` maps a document id to 3 (answers the question) or 1 (related, reasonable to surface).
  `relevantIds` is retained as the grade-3 subset. Grading is what stops nDCG collapsing into MRR;
  with one relevant document per query the ideal DCG is always 1 and the two metrics coincide.
- `golden-set/negative-search.json` — 15 **near-miss** negatives: plausible, in-domain questions
  with no answer in the corpus. An off-topic query ("sourdough starter") is trivially rejected and
  proves nothing.
- `golden-set/baseline.json` — gates only. It must never contain a pipeline knob: the retrieval
  settings are read from `application.yml`, so the gate cannot silently redefine the behaviour it
  is measuring.

## Baseline, measured 2026-08-03

| Metric | Measured | Gate |
|---|---|---|
| P@1 | 0.900 | 0.86 |
| MRR | 0.942 | 0.91 |
| graded nDCG@10 | 0.950 | 0.92 |
| Recall@candidate | 1.000 | 0.98 |
| Negative rejection | **0.000** | 0.00 |

**Recall@candidate** — was a relevant document inside the candidate window that reached the
reranker? — is the metric the fusion weights actually control. Without it, a change to the
vector/lexical blend is invisible to the gate, because everything downstream can only reorder what
the window already contains.

## Known weakness: near-miss negatives are not rejected

Negative rejection measures 0.0, not because the fixture is wrong but because it found something.
The near-miss negatives score 0.020–0.079 from the cross-encoder — **above** the 0.015
`rag.search.min-relevance-score` floor — while genuinely correct answers score 0.73–0.93 (median
0.93). The floor was calibrated against off-topic queries, which score below it, and does not
separate in-domain unanswerable ones.

Measured trade-off if the floor were raised:

| Floor | Negatives rejected | Correct answers lost |
|---|---|---|
| 0.015 (current) | 0 / 15 | 0 / 157 |
| 0.050 | 12 / 15 | 4 / 157 |
| 0.080 | 15 / 15 | 9 / 157 |

Raising the floor is a change to shipped ranking behaviour and is deliberately **not** made here.
Note also that the lexical-rescue path (term coverage ≥ 0.30) keeps a result regardless of the
floor, so the floor alone cannot reject every negative. The gate is set at the measured 0.0 so it
can only be tightened once a decision is made.
