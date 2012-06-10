package perf;

/**
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

import java.io.IOException;
import java.util.Collection;

import org.apache.lucene.search.CachingCollector;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.grouping.BlockGroupingCollector;
import org.apache.lucene.search.grouping.GroupDocs;
import org.apache.lucene.search.grouping.SearchGroup;
import org.apache.lucene.search.grouping.TopGroups;
import org.apache.lucene.search.grouping.term.TermAllGroupsCollector;
import org.apache.lucene.search.grouping.term.TermFirstPassGroupingCollector;
import org.apache.lucene.search.grouping.term.TermSecondPassGroupingCollector;
import org.apache.lucene.search.vectorhighlight.FieldQuery;
import org.apache.lucene.util.BytesRef;

final class SearchTask extends Task {
  private final String category;
  private final Query q;
  private final Sort s;
  private final Filter f;
  private final String group;
  private final int topN;
  private final boolean singlePassGroup;
  private final boolean doCountGroups;
  private final boolean doHilite;
  private TopDocs hits;
  private TopGroups<?> groupsResultBlock;
  private TopGroups<BytesRef> groupsResultTerms;
  private FieldQuery fieldQuery;

  public SearchTask(String category, Query q, Sort s, String group, Filter f, int topN, boolean doHilite) {
    this.category = category;
    this.q = q;
    this.s = s;
    this.f = f;
    if (group != null && group.startsWith("groupblock")) {
      this.group = "groupblock";
      this.singlePassGroup = group.equals("groupblock1pass");
      doCountGroups = true;
    } else {
      this.group = group;
      this.singlePassGroup = false;
      doCountGroups = false;
    }
    this.topN = topN;
    this.doHilite = doHilite;
  }

  @Override
  public Task clone() {
    Query q2 = q.clone();
    if (q2 == null) {
      throw new RuntimeException("q=" + q + " failed to clone");
    }
    if (singlePassGroup) {
      return new SearchTask(category, q2, s, "groupblock1pass", f, topN, doHilite);
    } else {
      return new SearchTask(category, q2, s, group, f, topN, doHilite);
    }
  }

  @Override
  public String getCategory() {
    return category;
  }

  @Override
  public void go(IndexState state) throws IOException {
    //System.out.println("go group=" + this.group + " single=" + singlePassGroup + " xxx=" + xxx + " this=" + this);
    final IndexSearcher searcher = state.mgr.acquire();

    try {
      if (doHilite) {
        fieldQuery = state.highlighter.getFieldQuery(q, searcher.getIndexReader());
      }

      if (group != null) {
        if (singlePassGroup) {
          final BlockGroupingCollector c = new BlockGroupingCollector(Sort.RELEVANCE, 10, true, state.groupEndFilter);
          searcher.search(q, c);
          groupsResultBlock = c.getTopGroups(null, 0, 0, 10, true);

          if (doHilite) {
            hilite(groupsResultBlock, state, searcher);
          }

        } else {
          //System.out.println("GB: " + group);
          final TermFirstPassGroupingCollector c1 = new TermFirstPassGroupingCollector(group, Sort.RELEVANCE, 10);
          final CachingCollector cCache = CachingCollector.create(c1, true, 32.0);

          final Collector c;
          final TermAllGroupsCollector allGroupsCollector;
          // Turn off AllGroupsCollector for now -- it's very slow:
          if (false && doCountGroups) {
            allGroupsCollector = new TermAllGroupsCollector(group);
            c = MultiCollector.wrap(allGroupsCollector, cCache);
          } else {
            allGroupsCollector = null;
            c = cCache;
          }
          
          searcher.search(q, c);

          final Collection<SearchGroup<BytesRef>> topGroups = c1.getTopGroups(0, true);
          if (topGroups != null) {
            final TermSecondPassGroupingCollector c2 = new TermSecondPassGroupingCollector(group, topGroups, Sort.RELEVANCE, null, 10, true, true, true);
            if (cCache.isCached()) {
              cCache.replay(c2);
            } else {
              searcher.search(q, c2);
            }
            groupsResultTerms = c2.getTopGroups(0);
            if (allGroupsCollector != null) {
              groupsResultTerms = new TopGroups<BytesRef>(groupsResultTerms,
                                                          allGroupsCollector.getGroupCount());
            }
            if (doHilite) {
              hilite(groupsResultTerms, state, searcher);
            }
          }
        }
      } else if (s == null && f == null) {
        hits = searcher.search(q, topN);
        if (doHilite) {
          hilite(hits, state, searcher);
        }
      } else if (s == null && f != null) {
        hits = searcher.search(q, f, topN);
        if (doHilite) {
          hilite(hits, state, searcher);
        }
      } else {
        hits = searcher.search(q, f, topN, s);
        if (doHilite) {
          hilite(hits, state, searcher);
        }
        /*
          final boolean fillFields = true;
          final boolean fieldSortDoTrackScores = true;
          final boolean fieldSortDoMaxScore = true;
          final TopFieldCollector c = TopFieldCollector.create(s, topN,
          fillFields,
          fieldSortDoTrackScores,
          fieldSortDoMaxScore,
          false);
          searcher.search(q, c);
          hits = c.topDocs();
        */
      }

      //System.out.println("TE: " + TermsEnum.getStats());
    } finally {
      state.mgr.release(searcher);
    }
  }

  private void hilite(TopGroups<?> groups, IndexState indexState, IndexSearcher searcher) throws IOException {
    for(GroupDocs<?> group : groups.groups) {
      for(ScoreDoc sd : group.scoreDocs) {
        hilite(sd.doc, indexState, searcher);
      }
    }
  }

  private void hilite(TopDocs hits, IndexState indexState, IndexSearcher searcher) throws IOException {
    for(ScoreDoc sd : hits.scoreDocs) {
      hilite(sd.doc, indexState, searcher);
    }
  }

  private void hilite(int docID, IndexState indexState, IndexSearcher searcher) throws IOException {
    String h = indexState.highlighter.getBestFragment(fieldQuery,
                                                      searcher.getIndexReader(), docID,
                                                      indexState.textFieldName,
                                                      100);
    //System.out.println("h=" + h + " q=" + q + " doc=" + docID + " title=" + searcher.doc(docID).get("title"));
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof SearchTask) {
      final SearchTask otherSearchTask = (SearchTask) other;
      if (!q.equals(otherSearchTask.q)) {
        return false;
      }
      if (s != null) {
        if (otherSearchTask.s != null) {
          if (!s.equals(otherSearchTask.s)) {
            return false;
          }
        } else {
          if (otherSearchTask.s != null) {
            return false;
          }
        }
      }
      if (topN != otherSearchTask.topN) {
        return false;
      }

      if (group != null && !group.equals(otherSearchTask.group)) {
        return false;
      } else if (otherSearchTask.group != null) {
        return false;
      }

      if (f != null) {
        if (otherSearchTask.f == null) {
          return false;
        } else if (!f.equals(otherSearchTask.f)) {
          return false;
        }
      } else if (otherSearchTask.f != null) {
        return false;
      }

      return true;
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    int hashCode = q.hashCode();
    if (s != null) {
      hashCode ^= s.hashCode();
    }
    if (group != null) {
      hashCode ^= group.hashCode();
    }
    if (f != null) {
      hashCode ^= f.hashCode();
    }
    hashCode *= topN;
    return hashCode;
  }

  @Override
  public long checksum() {
    final long PRIME = 641;
    long sum = 0;
    //System.out.println("checksum q=" + q + " f=" + f);
    if (group != null) {
      if (singlePassGroup) {
        for(GroupDocs<?> groupDocs : groupsResultBlock.groups) {
          sum += groupDocs.totalHits;
          for(ScoreDoc hit : groupDocs.scoreDocs) {
            sum = sum * PRIME + hit.doc;
          }
        }
      } else {
        for(GroupDocs<BytesRef> groupDocs : groupsResultTerms.groups) {
          sum += groupDocs.totalHits;
          for(ScoreDoc hit : groupDocs.scoreDocs) {
            sum = sum * PRIME + hit.doc;
            if (hit instanceof FieldDoc) {
              final FieldDoc fd = (FieldDoc) hit;
              if (fd.fields != null) {
                for(Object o : fd.fields) {
                  sum = sum * PRIME + o.hashCode();
                }
              }
            }
          }
        }
      }
    } else {
      sum = hits.totalHits;
      for(ScoreDoc hit : hits.scoreDocs) {
        //System.out.println("  " + hit.doc);
        sum = sum * PRIME + hit.doc;
        if (hit instanceof FieldDoc) {
          final FieldDoc fd = (FieldDoc) hit;
          if (fd.fields != null) {
            for(Object o : fd.fields) {
              sum = sum * PRIME + o.hashCode();
            }
          }
        }
      }
      //System.out.println("  final=" + sum);
    }

    return sum;
  }

  @Override
  public String toString() {
    return "cat=" + category + " q=" + q + " s=" + s + " f=" + f + " group=" + (group == null ?  null : group.replace("\n", "\\n")) +
      (group == null ? " hits=" + hits.totalHits :
       " groups=" + (singlePassGroup ?
                     (groupsResultBlock.groups.length + " hits=" + groupsResultBlock.totalHitCount + " groupTotHits=" + groupsResultBlock.totalGroupedHitCount + " totGroupCount=" + groupsResultBlock.totalGroupCount) :
                     (groupsResultTerms.groups.length + " hits=" + groupsResultTerms.totalHitCount + " groupTotHits=" + groupsResultTerms.totalGroupedHitCount + " totGroupCount=" + groupsResultTerms.totalGroupCount)));
  }

  @Override
  public void printResults(IndexState state) throws IOException {
    if (group != null) {
      if (singlePassGroup) {
        for(GroupDocs<?> groupDocs : groupsResultBlock.groups) {
          System.out.println("  group=null" + " totalHits=" + groupDocs.totalHits + " groupRelevance=" + groupDocs.groupSortValues[0]);
          for(ScoreDoc hit : groupDocs.scoreDocs) {
            System.out.println("    doc=" + hit.doc + " score=" + hit.score);
          }
        }
      } else {
        for(GroupDocs<BytesRef> groupDocs : groupsResultTerms.groups) {
          System.out.println("  group=" + (groupDocs.groupValue == null ? "null" : groupDocs.groupValue.utf8ToString().replace("\n", "\\n")) + " totalHits=" + groupDocs.totalHits + " groupRelevance=" + groupDocs.groupSortValues[0]);
          for(ScoreDoc hit : groupDocs.scoreDocs) {
            System.out.println("    doc=" + hit.doc + " score=" + hit.score);
          }
        }
      }
    } else if (hits instanceof TopFieldDocs) {
      final TopFieldDocs fieldHits = (TopFieldDocs) hits;
      for(int idx=0;idx<hits.scoreDocs.length;idx++) {
        FieldDoc hit = (FieldDoc) hits.scoreDocs[idx];
        final Object v = hit.fields[0];
        final String vs;
        if (v instanceof Long) {
          vs = v.toString();
        } else {
          vs = ((BytesRef) v).utf8ToString();
        }
        System.out.println("  doc=" + state.docIDToID[hit.doc] + " field=" + vs);
      }
    } else {
      for(ScoreDoc hit : hits.scoreDocs) {
        System.out.println("  doc=" + state.docIDToID[hit.doc] + " score=" + hit.score);
      }
    }
  }
}
