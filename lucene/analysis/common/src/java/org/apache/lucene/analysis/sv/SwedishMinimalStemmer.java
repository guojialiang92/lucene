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

/*
 * The code is inspired from original code located at: http://members.unine.ch/jacques.savoy/clef/
 *
 * Full copyright for that code follows:
 */

/*
 * Copyright (c) 2005, Jacques Savoy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer. Redistributions in binary
 * form must reproduce the above copyright notice, this list of conditions and
 * the following disclaimer in the documentation and/or other materials
 * provided with the distribution. Neither the name of the author nor the names
 * of its contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package org.apache.lucene.analysis.sv;

import static org.apache.lucene.analysis.util.StemmerUtil.endsWith;

/**
 * Minimal Stemmer for Swedish. The algorithm is an adapted version of the SwedishLightStemmer, but
 * only stripping the most common plural suffixes for nouns: -ar/arne/arna/aren, -at, -er/erna, -et,
 * -or/orna, -en. We do not strip -an or -ans suffixes, since that would require a large dictionary
 * of exceptions.
 *
 * @since 9.0.0
 */
class SwedishMinimalStemmer {

  int stem(char[] s, int len) {
    if (len > 4 && s[len - 1] == 's') len--;

    if (len > 6
        && (endsWith(s, len, "arne")
            || endsWith(s, len, "erna")
            || endsWith(s, len, "arna")
            || endsWith(s, len, "orna")
            || endsWith(s, len, "aren"))) return len - 4;

    if (len > 5 && (endsWith(s, len, "are"))) return len - 3;

    if (len > 4
        && (endsWith(s, len, "ar")
            || endsWith(s, len, "at")
            || endsWith(s, len, "er")
            || endsWith(s, len, "et")
            || endsWith(s, len, "or")
            || endsWith(s, len, "en"))) return len - 2;

    if (len > 3)
      switch (s[len - 1]) {
        case 'a':
        case 'e':
        case 'n':
          return len - 1;
      }

    return len;
  }
}
