#!/usr/bin/env python3
"""Apply and validate the Assisted Claim diagnostic source transformations.

The primary activity patcher intentionally stores Java snippets in Python
multiline strings. This wrapper normalizes embedded display newlines to Java
``\\n`` escape sequences, then patches ApiManager so Retrofit/OkHttp root causes
are preserved instead of being replaced by a generic Claim-init error.
"""

from pathlib import Path
import runpy

ACTIVITY_PATCHER = Path("scripts/patch_claim_diagnostics.py")
API_PATCHER = Path("scripts/patch_claim_api_diagnostics.py")
TARGET = Path("app/src/main/java/com/espressif/ui/activities/ClaimingActivity.java")


def main() -> None:
    runpy.run_path(str(ACTIVITY_PATCHER), run_name="__main__")

    source = TARGET.read_text(encoding="utf-8")
    line_feed = chr(10)
    slash_n = chr(92) + "n"

    replacements = {
        'setText(stage + "' + line_feed + '" + detail);':
            'setText(stage + "' + slash_n + '" + detail);',
        '"cloud-initiate-timeout' + line_feed
        + 'No response from claim service within 30 seconds");':
            '"cloud-initiate-timeout' + slash_n
            + 'No response from claim service within 30 seconds");',
    }

    for broken, fixed in replacements.items():
        count = source.count(broken)
        if count != 1:
            raise RuntimeError(
                "Expected exactly one generated Java newline pattern, "
                f"found {count}: {broken!r}"
            )
        source = source.replace(broken, fixed, 1)

    TARGET.write_text(source, encoding="utf-8")

    remaining = [pattern for pattern in replacements if pattern in source]
    if remaining:
        raise RuntimeError("Generated Java still contains unescaped display newlines")

    runpy.run_path(str(API_PATCHER), run_name="__main__")

    print(f"Applied and normalized diagnostics in {TARGET}")


if __name__ == "__main__":
    main()
