import json
import pathlib
import sys

results = pathlib.Path(sys.argv[1])
expected = json.loads(pathlib.Path("fixtures/expected.json").read_text())
report = {}
for name, phrases in expected.items():
    text = (results / f"{name}.txt").read_text()
    metrics = json.loads((results / f"{name}.json").read_text())
    normalized = " ".join(text.casefold().split())
    for phrase in phrases:
        if phrase.casefold() not in normalized:
            raise AssertionError(f"{name}: missing {phrase!r} in {text!r}")
    if metrics["truncated"] or metrics["generated_tokens"] < 1:
        raise AssertionError(f"{name}: incomplete generation: {metrics}")
    if not 0 < metrics["peak_rss_kib"] < 3.5 * 1024 * 1024:
        raise AssertionError(f"{name}: exceeds 3.5 GiB inference memory budget: {metrics}")
    report[name] = {"output": text, **metrics}
(results / "verified.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
