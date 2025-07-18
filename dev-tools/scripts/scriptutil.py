# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import os
import re
import subprocess
import sys
import time
import urllib.request
from collections.abc import Callable
from enum import Enum
from pathlib import Path
from re import Match, Pattern
from typing import Self, override


class Version:
  def __init__(self, major: int, minor: int, bugfix: int, prerelease: int):
    super().__init__()
    self.major = major
    self.minor = minor
    self.bugfix = bugfix
    self.prerelease = prerelease
    self.previous_dot_matcher = self.make_previous_matcher()
    self.dot = "%d.%d.%d" % (self.major, self.minor, self.bugfix)
    self.constant = "LUCENE_%d_%d_%d" % (self.major, self.minor, self.bugfix)

  @classmethod
  def parse(cls, value: str):
    match = re.search(r"(\d+)\.(\d+).(\d+)(.1|.2)?", value)
    if match is None:
      raise argparse.ArgumentTypeError("Version argument must be of format x.y.z(.1|.2)?")
    parts = [int(v) for v in match.groups()[:-1]]
    parts.append({None: 0, ".1": 1, ".2": 2}[match.groups()[-1]])
    return Version(*parts)

  @override
  def __str__(self):
    return self.dot

  def make_previous_matcher(self, prefix: str = "", suffix: str = "", sep: str = "\\."):
    if self.is_bugfix_release():
      pattern = "%s%s%s%s%d" % (self.major, sep, self.minor, sep, self.bugfix - 1)
    elif self.is_minor_release():
      pattern = "%s%s%d%s\\d+" % (self.major, sep, self.minor - 1, sep)
    else:
      pattern = "%d%s\\d+%s\\d+" % (self.major - 1, sep, sep)

    return re.compile(prefix + "(" + pattern + ")" + suffix)

  def is_bugfix_release(self):
    return self.bugfix != 0

  def is_minor_release(self):
    return self.bugfix == 0 and self.minor != 0

  def is_major_release(self):
    return self.bugfix == 0 and self.minor == 0

  def on_or_after(self, other: Self):
    return self.major > other.major or (
      self.major == other.major and (self.minor > other.minor or (self.minor == other.minor and (self.bugfix > other.bugfix or (self.bugfix == other.bugfix and self.prerelease >= other.prerelease))))
    )

  def gt(self, other: Self):
    return self.major > other.major or (self.major == other.major and self.minor > other.minor) or (self.major == other.major and self.minor == other.minor and self.bugfix > other.bugfix)

  def is_back_compat_with(self, other: Self):
    if not self.on_or_after(other):
      raise Exception("Back compat check disallowed for newer version: %s < %s" % (self, other))
    return other.major + 1 >= self.major


def run(cmd: str, cwd: str | None = None):
  try:
    output = subprocess.check_output(cmd, shell=True, stderr=subprocess.STDOUT, cwd=cwd)
  except subprocess.CalledProcessError as e:
    print(e.output.decode("utf-8"))
    raise e
  return output.decode("utf-8")


def update_file(filename: str, line_re: Pattern[str], edit: Callable[[list[str], Match[str], str], bool | None], append: Callable[[list[str], bool], bool] | None = None):
  infile = open(filename)
  buffer: list[str] = []

  changed = False
  for line in infile:
    if not changed:
      match = line_re.search(line)
      if match:
        changed = edit(buffer, match, line)
        if changed is None:
          return False
        continue
    buffer.append(line)
  if append:
    changed = append(buffer, changed)  # in the case did not change in edit but have an append function
  if not changed:
    raise Exception("Could not find %s in %s" % (line_re, filename))
  with open(filename, "w") as f:
    f.write("".join(buffer))
  return True


# branch types are "release", "stable" and "unstable"
class BranchType(Enum):
  unstable = 1
  stable = 2
  release = 3


def find_branch_type():
  output = subprocess.check_output("git status", shell=True)
  for line in output.split(b"\n"):
    if line.startswith(b"On branch "):
      branchName = line.split(b" ")[-1]
      break
  else:
    raise Exception("git status missing branch name")

  if branchName == b"main":
    return BranchType.unstable
  if re.match(r"branch_(\d+)x", branchName.decode("UTF-8")):
    return BranchType.stable
  if re.match(r"branch_(\d+)_(\d+)", branchName.decode("UTF-8")):
    return BranchType.release
  raise Exception("Cannot run %s on feature branch" % sys.argv[0].rsplit("/", 1)[-1])


def download(name: str, urlString: str, tmpDir: str, quiet: bool = False, force_clean: bool = True):
  if not quiet:
    print("Downloading %s" % urlString)
  startTime = time.time()
  fileName = "%s/%s" % (tmpDir, name)
  if not force_clean and os.path.exists(fileName):
    if not quiet and fileName.find(".asc") == -1:
      print("    already done: %.1f MB" % (os.path.getsize(fileName) / 1024.0 / 1024.0))
    return
  try:
    attemptDownload(urlString, fileName)
  except Exception as e:
    print("Retrying download of url %s after exception: %s" % (urlString, e))
    try:
      attemptDownload(urlString, fileName)
    except Exception as e:
      raise RuntimeError('failed to download url "%s"' % urlString) from e
  if not quiet and fileName.find(".asc") == -1:
    t = time.time() - startTime
    sizeMB = os.path.getsize(fileName) / 1024.0 / 1024.0
    print("    %.1f MB in %.2f sec (%.1f MB/sec)" % (sizeMB, t, sizeMB / t))


def attemptDownload(urlString: str, fileName: str):
  fIn = urllib.request.urlopen(urlString)
  fOut = open(fileName, "wb")
  success = False
  try:
    while True:
      s = fIn.read(65536)
      if s == b"":
        break
      fOut.write(s)
    fOut.close()
    fIn.close()
    success = True
  finally:
    fIn.close()
    fOut.close()
    if not success:
      os.remove(fileName)


def find_current_version():
  version_prop_re = re.compile(r"version\.base=(.*)")
  script_path = os.path.dirname(os.path.realpath(__file__))
  top_level_dir = os.path.join(Path("%s/" % script_path).resolve(), os.path.pardir, os.path.pardir)
  match = version_prop_re.search(open("%s/build-options.properties" % top_level_dir).read())
  assert match
  return match.group(1).strip()


if __name__ == "__main__":
  print("This is only a support module, it cannot be run")
  sys.exit(1)
