#!/usr/bin/env python3
"""Builds app/src/main/assets/dict/en_US.txt from an AOSP LatinIME word list.

Source: packages/inputmethods/LatinIME/dictionaries/en_US_wordlist.combined.gz
        (Apache License 2.0, The Android Open Source Project), e.g. from
        https://github.com/LineageOS/android_packages_inputmethods_LatinIME

Usage: python3 tools/build_wordlist.py en_US_wordlist.combined.gz app/src/main/assets/dict/en_US.txt

Keeps words with frequency >= MIN_FREQ and drops entries flagged offensive or "nonword".
Output: one "word<TAB>frequency" line per word, sorted case-insensitively.
"""
import gzip
import sys

MIN_FREQ = 30
DROP_FLAGS = {"offensive", "nonword"}


def parse(path):
    with gzip.open(path, "rt", encoding="utf-8") as fh:
        for line in fh:
            if not line.startswith(" word="):
                continue
            attrs = {}
            for part in line.strip().split(","):
                key, _, value = part.partition("=")
                attrs[key] = value
            word = attrs.get("word", "")
            if not word or attrs.get("possibly_offensive") == "true":
                continue
            if attrs.get("flags") in DROP_FLAGS:
                continue
            try:
                freq = int(attrs.get("f", "0"))
            except ValueError:
                continue
            if freq >= MIN_FREQ and "\t" not in word:
                yield word, freq


def main():
    src, dst = sys.argv[1], sys.argv[2]
    best = {}
    for word, freq in parse(src):
        if best.get(word, -1) < freq:
            best[word] = freq
    rows = sorted(best.items(), key=lambda kv: (kv[0].lower(), -kv[1]))
    with open(dst, "w", encoding="utf-8", newline="\n") as out:
        out.write("# English (US) word list derived from AOSP LatinIME, Apache License 2.0.\n")
        out.write("# word<TAB>frequency (0-255). Regenerate with tools/build_wordlist.py.\n")
        for word, freq in rows:
            out.write(f"{word}\t{freq}\n")
    print(f"wrote {len(rows)} words to {dst}")


if __name__ == "__main__":
    main()
