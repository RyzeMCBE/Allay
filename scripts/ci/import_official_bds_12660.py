#!/usr/bin/env python3
"""Verified BDS 1.26.60 import for Allay; does not replace the client network palette."""
import hashlib
import json
import shutil
from pathlib import Path

BASE = Path("data/resources")
UNPACKED = BASE / "unpacked"
SOURCE = Path(".ci/endstone-export/data")
MANIFEST = Path(".ci/endstone-export/manifest.json")
REQUIRED = ("block_types.json", "block_states.json", "block_tags.json", "items.json",
            "item_tags.json", "item_components.nbt", "creative_items.nbt",
            "creative_groups.json", "recipes.json", "biomes.json", "block_palette.nbt")


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def main():
    manifest = load(MANIFEST)
    assert manifest["bds_version"] in ("26.60", "1.26.60"), "Wrong BDS version"
    assert manifest["server_started"] and manifest["export_completed"] and not manifest["errors"], "Bad BDS run"
    counts = {"block_types": 1480, "block_states": 23960, "items": 2080, "biomes": 89}
    for name, expected in counts.items():
        assert manifest["counts"][name] == expected, "Bad count for " + name
    for name in REQUIRED:
        source = SOURCE / name
        assert source.is_file(), "Missing BDS file: " + name
        assert hashlib.sha256(source.read_bytes()).hexdigest() == manifest["files"][name]["sha256"], "Bad hash: " + name

    types = load(SOURCE / "block_types.json")
    states = load(SOURCE / "block_states.json")
    items = load(SOURCE / "items.json")
    assert (len(types), len(states), len(items)) == (1480, 23960, 2080)
    for name in ("minecraft:disc_press", "minecraft:ice_crystal", "minecraft:icicle"):
        assert name in types, "Missing new block: " + name

    # Endstone measurements omit translations. Reuse Allay's existing vetted
    # translation strings, with clearly marked provisional values for new IDs.
    old_states = load(UNPACKED / "block_states_raw.json")
    translations = {}
    for state in old_states:
        value = state.get("translationKey")
        if value and value != "null":
            translations.setdefault(state["name"], value.removeprefix("minecraft:"))
    new_blocks = set()
    for state in states:
        name = state["name"]
        key = translations.get(name)
        if not key:
            key = "tile." + name.partition(":")[2] + ".name"
            new_blocks.add(name)
        state["translationKey"] = key
    save(UNPACKED / "block_states_raw.json", states)

    old_items = load(BASE / "items.json")
    new_items = []
    for name, item in items.items():
        value = old_items.get(name, {}).get("translationKey")
        if not value:
            value = "item." + name.partition(":")[2] + ".name"
            new_items.append(name)
        item["translationKey"] = value.removeprefix("minecraft:")
    save(UNPACKED / "items_raw.json", items)

    for name, target in {
        "block_types.json": BASE / "block_types.json",
        "block_tags.json": UNPACKED / "block_tags.json",
        "item_tags.json": UNPACKED / "item_tags.json",
        "recipes.json": BASE / "recipes.json",
        "item_components.nbt": BASE / "item_components.nbt",
        "creative_items.nbt": BASE / "creative_items.nbt",
        "creative_groups.json": BASE / "creative_groups.json",
        "biomes.json": UNPACKED / "biomes_bds_1_26_60.json",
    }.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SOURCE / name, target)

    # Official 2225 client palette is already pinned from CloudburstMC/Data.
    # Endstone's server-side palette is a different representation; do not mix.
    save(UNPACKED / "bds_1_26_60_source.json", {
        "source_repository": manifest["repository"],
        "source_run_id": manifest["workflow_run_id"],
        "source_commit": manifest["commit"],
        "bds_version": manifest["bds_version"],
        "counts": manifest["counts"],
        "sha256": {n: manifest["files"][n]["sha256"] for n in REQUIRED},
        "provisional_block_translation_keys": sorted(new_blocks),
        "provisional_item_translation_keys": sorted(new_items),
        "jigsaw_captured": manifest.get("jigsaw_packet_captured", False),
    })
    print("Imported official 1.26.60: 1480 blocks, 23960 states, 2080 items, 89 biomes")
    print("Provisional translations:", sorted(new_blocks), sorted(new_items))


if __name__ == "__main__":
    main()
