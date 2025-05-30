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

package org.apache.lucene.analysis.opennlp.tools;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTagFormat;
import opennlp.tools.postag.POSTagger;
import opennlp.tools.postag.POSTaggerME;

/**
 * Supply OpenNLP Parts-Of-Speech Tagging tool Requires binary models from OpenNLP project on
 * SourceForge.
 */
public class NLPPOSTaggerOp {
  private final POSTagger tagger;

  public NLPPOSTaggerOp(POSModel model) {
    tagger = new POSTaggerME(model, POSTagFormat.PENN);
  }

  public synchronized String[] getPOSTags(String[] words) {
    return tagger.tag(words);
  }
}
