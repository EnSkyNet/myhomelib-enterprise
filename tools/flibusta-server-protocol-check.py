#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
profile = (root / "myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogSourceProfile.java").read_text(encoding="utf-8")
required = [
    "flibusta_online_fb2.inpx",
    "flibusta_online_fb2.info",
    "flibusta_online_fb2.zip",
    "extra_flibusta_online_fb2.info",
    "extra_flibusta_online_fb2.zip",
]
for value in required:
    assert value in profile, f"missing canonical Flibusta protocol entry: {value}"
for stale in ["flibusta_online.inpx", "last_flibusta.info", "last_flibusta_extra.info", "flubusta_update.zip", "flibusta_extra_update.zip"]:
    assert stale not in profile, f"stale Flibusta protocol entry returned: {stale}"
print("Flibusta server protocol: PASS")
