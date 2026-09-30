# Placeholder validation corpus

**This is NOT the AI4Bharat corpus or any real speech dataset.** It is a
small, hand-authored set of short alert/distress-scenario sentences in
the 10 required languages (§5), written for one purpose only: to exercise
the semantic codec pipeline (tokenizer → compact IDs → optional entropy
stage) end to end and produce a *real, executed* bitrate comparison
against raw UTF-8, so §22's "measure before you call it low-bitrate"
requirement has at least a first honest data point instead of zero data
points.

**What this corpus does NOT establish:** real vocabulary coverage, real
WER/CER, real bitrate numbers representative of production traffic, or
anything about the 9 Indic scripts' actual statistical structure at
scale. Gate 4 and Gate 9 (§16) both require a REAL corpus — sourced from
AI4Bharat's datasets or an equivalent open, licensed, speaker-disjoint
Indic text/speech corpus — before any bitrate or accuracy number from
this pipeline can be reported to a judge as representative. Building
that corpus ingestion pipeline is out of this session's Part IV scope
and is tracked as an open task in `TODO.md`.

Sentences below are short, alert-domain-relevant (matching the STT
domain lexicon in §19: help, fire, flood, medical, evacuate, and their
translations), and were written directly for this repository — not
scraped, not copied from a copyrighted source.
