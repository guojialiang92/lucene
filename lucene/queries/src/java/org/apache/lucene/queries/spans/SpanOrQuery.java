/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.queries.spans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.PriorityQueue;

/** Matches the union of its clauses. */
public final class SpanOrQuery extends SpanQuery {
  private List<SpanQuery> clauses;
  private String field;

  /** Construct a SpanOrQuery merging the provided clauses. All clauses must have the same field. */
  public SpanOrQuery(SpanQuery... clauses) {
    this.clauses = new ArrayList<>(clauses.length);
    for (SpanQuery seq : clauses) {
      addClause(seq);
    }
  }

  /** Adds a clause to this query */
  private final void addClause(SpanQuery clause) {
    if (field == null) {
      field = clause.getField();
    } else if (clause.getField() != null && !clause.getField().equals(field)) {
      throw new IllegalArgumentException("Clauses must have same field.");
    }
    this.clauses.add(clause);
  }

  /** Return the clauses whose spans are matched. */
  public SpanQuery[] getClauses() {
    return clauses.toArray(new SpanQuery[clauses.size()]);
  }

  @Override
  public String getField() {
    return field;
  }

  @Override
  public Query rewrite(IndexSearcher indexSearcher) throws IOException {
    SpanOrQuery rewritten = new SpanOrQuery();
    boolean actuallyRewritten = false;
    for (int i = 0; i < clauses.size(); i++) {
      SpanQuery c = clauses.get(i);
      SpanQuery query = (SpanQuery) c.rewrite(indexSearcher);
      actuallyRewritten |= query != c;
      rewritten.addClause(query);
    }
    if (actuallyRewritten) {
      return rewritten;
    }
    return super.rewrite(indexSearcher);
  }

  @Override
  public void visit(QueryVisitor visitor) {
    if (visitor.acceptField(getField()) == false) {
      return;
    }
    QueryVisitor v = visitor.getSubVisitor(BooleanClause.Occur.SHOULD, this);
    for (SpanQuery q : clauses) {
      q.visit(v);
    }
  }

  @Override
  public String toString(String field) {
    StringBuilder buffer = new StringBuilder();
    buffer.append("spanOr([");
    Iterator<SpanQuery> i = clauses.iterator();
    while (i.hasNext()) {
      SpanQuery clause = i.next();
      buffer.append(clause.toString(field));
      if (i.hasNext()) {
        buffer.append(", ");
      }
    }
    buffer.append("])");
    return buffer.toString();
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && clauses.equals(((SpanOrQuery) other).clauses);
  }

  @Override
  public int hashCode() {
    return classHash() ^ clauses.hashCode();
  }

  @Override
  public SpanWeight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
      throws IOException {
    List<SpanWeight> subWeights = new ArrayList<>(clauses.size());
    for (SpanQuery q : clauses) {
      subWeights.add(q.createWeight(searcher, scoreMode, boost));
    }
    return new SpanOrWeight(
        searcher, scoreMode.needsScores() ? getTermStates(subWeights) : null, subWeights, boost);
  }

  /**
   * Creates SpanOrQuery scorer instances
   *
   * @lucene.internal
   */
  public class SpanOrWeight extends SpanWeight {

    final List<SpanWeight> subWeights;

    public SpanOrWeight(
        IndexSearcher searcher,
        Map<Term, TermStates> terms,
        List<SpanWeight> subWeights,
        float boost)
        throws IOException {
      super(SpanOrQuery.this, searcher, terms, boost);
      this.subWeights = subWeights;
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      for (Weight w : subWeights) {
        if (w.isCacheable(ctx) == false) return false;
      }
      return true;
    }

    @Override
    public void extractTermStates(Map<Term, TermStates> contexts) {
      for (SpanWeight w : subWeights) {
        w.extractTermStates(contexts);
      }
    }

    @Override
    public Spans getSpans(final LeafReaderContext context, Postings requiredPostings)
        throws IOException {

      ArrayList<Spans> subSpans = new ArrayList<>(clauses.size());

      for (SpanWeight w : subWeights) {
        Spans spans = w.getSpans(context, requiredPostings);
        if (spans != null) {
          subSpans.add(spans);
        }
      }

      if (subSpans.size() == 0) {
        return null;
      } else if (subSpans.size() == 1) {
        return subSpans.get(0);
      }

      SpanDisiPriorityQueue byDocQueue = new SpanDisiPriorityQueue(subSpans.size());
      for (Spans spans : subSpans) {
        byDocQueue.add(new SpanDisiWrapper(spans));
      }

      PriorityQueue<Spans> byPositionQueue =
          PriorityQueue.usingComparator(
              subSpans.size(), // when empty use -1
              Comparator.comparingInt(Spans::startPosition).thenComparingInt(Spans::endPosition));

      return new Spans() {
        Spans topPositionSpans = null;

        @Override
        public int nextDoc() throws IOException {
          topPositionSpans = null;
          SpanDisiWrapper topDocSpans = byDocQueue.top();
          int currentDoc = topDocSpans.doc;
          do {
            topDocSpans.doc = topDocSpans.iterator.nextDoc();
            topDocSpans = byDocQueue.updateTop();
          } while (topDocSpans.doc == currentDoc);
          return topDocSpans.doc;
        }

        @Override
        public int advance(int target) throws IOException {
          topPositionSpans = null;
          SpanDisiWrapper topDocSpans = byDocQueue.top();
          do {
            topDocSpans.doc = topDocSpans.iterator.advance(target);
            topDocSpans = byDocQueue.updateTop();
          } while (topDocSpans.doc < target);
          return topDocSpans.doc;
        }

        @Override
        public int docID() {
          SpanDisiWrapper topDocSpans = byDocQueue.top();
          return topDocSpans.doc;
        }

        @Override
        public TwoPhaseIterator asTwoPhaseIterator() {
          float sumMatchCost = 0; // See also DisjunctionScorer.asTwoPhaseIterator()
          long sumApproxCost = 0;

          for (SpanDisiWrapper w : byDocQueue) {
            if (w.twoPhaseView != null) {
              long costWeight = (w.cost <= 1) ? 1 : w.cost;
              sumMatchCost += w.twoPhaseView.matchCost() * costWeight;
              sumApproxCost += costWeight;
            }
          }

          if (sumApproxCost == 0) { // no sub spans supports approximations
            computePositionsCost();
            return null;
          }

          final float matchCost = sumMatchCost / sumApproxCost;

          return new TwoPhaseIterator(new SpanDisjunctionDISIApproximation(byDocQueue)) {
            @Override
            public boolean matches() throws IOException {
              return twoPhaseCurrentDocMatches();
            }

            @Override
            public float matchCost() {
              return matchCost;
            }
          };
        }

        float positionsCost = -1;

        void computePositionsCost() {
          float sumPositionsCost = 0;
          long sumCost = 0;
          for (SpanDisiWrapper w : byDocQueue) {
            long costWeight = (w.cost <= 1) ? 1 : w.cost;
            sumPositionsCost += w.spans.positionsCost() * costWeight;
            sumCost += costWeight;
          }
          positionsCost = sumPositionsCost / sumCost;
        }

        @Override
        public float positionsCost() {
          // This may be called when asTwoPhaseIterator returned null,
          // which happens when none of the sub spans supports approximations.
          assert positionsCost > 0;
          return positionsCost;
        }

        int lastDocTwoPhaseMatched = -1;

        boolean twoPhaseCurrentDocMatches() throws IOException {
          SpanDisiWrapper listAtCurrentDoc = byDocQueue.topList();
          // remove the head of the list as long as it does not match
          final int currentDoc = listAtCurrentDoc.doc;
          while (listAtCurrentDoc.twoPhaseView != null) {
            if (listAtCurrentDoc.twoPhaseView.matches()) {
              // use this spans for positions at current doc:
              listAtCurrentDoc.lastApproxMatchDoc = currentDoc;
              break;
            }
            // do not use this spans for positions at current doc:
            listAtCurrentDoc.lastApproxNonMatchDoc = currentDoc;
            listAtCurrentDoc = listAtCurrentDoc.next;
            if (listAtCurrentDoc == null) {
              return false;
            }
          }
          lastDocTwoPhaseMatched = currentDoc;
          topPositionSpans = null;
          return true;
        }

        void fillPositionQueue() throws IOException { // called at first nextStartPosition
          assert byPositionQueue.size() == 0;
          // add all matching Spans at current doc to byPositionQueue
          SpanDisiWrapper listAtCurrentDoc = byDocQueue.topList();
          while (listAtCurrentDoc != null) {
            Spans spansAtDoc = listAtCurrentDoc.spans;
            if (lastDocTwoPhaseMatched
                == listAtCurrentDoc.doc) { // matched by DisjunctionDisiApproximation
              if (listAtCurrentDoc.twoPhaseView != null) { // matched by approximation
                if (listAtCurrentDoc.lastApproxNonMatchDoc
                    == listAtCurrentDoc.doc) { // matches() returned false
                  spansAtDoc = null;
                } else {
                  if (listAtCurrentDoc.lastApproxMatchDoc != listAtCurrentDoc.doc) {
                    if (!listAtCurrentDoc.twoPhaseView.matches()) {
                      spansAtDoc = null;
                    }
                  }
                }
              }
            }

            if (spansAtDoc != null) {
              assert spansAtDoc.docID() == listAtCurrentDoc.doc;
              assert spansAtDoc.startPosition() == -1;
              spansAtDoc.nextStartPosition();
              assert spansAtDoc.startPosition() != NO_MORE_POSITIONS;
              byPositionQueue.add(spansAtDoc);
            }
            listAtCurrentDoc = listAtCurrentDoc.next;
          }
          assert byPositionQueue.size() > 0;
        }

        @Override
        public int nextStartPosition() throws IOException {
          if (topPositionSpans == null) {
            byPositionQueue.clear();
            fillPositionQueue(); // fills byPositionQueue at first position
            topPositionSpans = byPositionQueue.top();
          } else {
            topPositionSpans.nextStartPosition();
            topPositionSpans = byPositionQueue.updateTop();
          }
          return topPositionSpans.startPosition();
        }

        @Override
        public int startPosition() {
          return topPositionSpans == null ? -1 : topPositionSpans.startPosition();
        }

        @Override
        public int endPosition() {
          return topPositionSpans == null ? -1 : topPositionSpans.endPosition();
        }

        @Override
        public int width() {
          return topPositionSpans.width();
        }

        @Override
        public void collect(SpanCollector collector) throws IOException {
          if (topPositionSpans != null) topPositionSpans.collect(collector);
        }

        @Override
        public String toString() {
          return "spanOr("
              + SpanOrQuery.this
              + ")@"
              + docID()
              + ": "
              + startPosition()
              + " - "
              + endPosition();
        }

        long cost = -1;

        @Override
        public long cost() {
          if (cost == -1) {
            cost = 0;
            for (Spans spans : subSpans) {
              cost += spans.cost();
            }
          }
          return cost;
        }
      };
    }
  }
}
