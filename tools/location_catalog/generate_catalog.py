#!/usr/bin/env python3
"""Generates V2rayNG/app/src/main/assets/location_catalog.json.

Inputs (pinned in docs/CUSTOM_LOCATION_CATALOG.md):
  * ISO 3166-2 first-level subdivisions: Python `pycountry` (Debian iso-codes, LGPL-2.1+).
  * Cities: npm `all-the-cities` (GeoNames, CC BY 4.0) filtered by population.
Country names are NOT bundled: the app derives ISO 3166-1 alpha-2 names from the platform
(java.util.Locale), so they follow the device language.

Usage: python3 generate_catalog.py <cities.json> <out.json> [minPopulation]
"""
import json, sys, datetime
import pycountry

cities_path, out_path = sys.argv[1], sys.argv[2]
min_pop = int(sys.argv[3]) if len(sys.argv) > 3 else 500000

alpha2 = {c.alpha_2 for c in pycountry.countries}
regions = {}
for s in pycountry.subdivisions:
    if s.parent_code is not None:
        continue  # first-level only, keeps the asset small
    regions.setdefault(s.country_code, []).append([s.code, s.name])
for cc in regions:
    regions[cc].sort(key=lambda r: r[0])
region_codes = {r[0] for rs in regions.values() for r in rs}

cities = {}
linked = 0
total = 0
for c in json.load(open(cities_path)):
    if c["cc"] not in alpha2 or c["pop"] < min_pop:
        continue
    total += 1
    candidate = f'{c["cc"]}-{c["admin1"]}' if c["admin1"] else ""
    # GeoNames admin1 codes only equal ISO 3166-2 suffixes for some countries (e.g. US).
    # Link only when the result is a real ISO 3166-2 code; otherwise leave unlinked.
    region = candidate if candidate in region_codes else ""
    linked += 1 if region else 0
    cities.setdefault(c["cc"], []).append(
        [c["id"], c["name"], region, round(c["lat"], 4), round(c["lon"], 4), c["pop"]])
for cc in cities:
    cities[cc].sort(key=lambda r: (-r[5], r[1]))

doc = {
    "schema": 1,
    "meta": {
        "generated": datetime.date.today().isoformat(),
        "cityPopulationMin": min_pop,
        "pycountry": pycountry.__version__ if hasattr(pycountry, "__version__") else "unknown",
        "cityCount": total,
        "cityRegionLinked": linked,
    },
    "regions": dict(sorted(regions.items())),
    "cities": dict(sorted(cities.items())),
}
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
print(f"regions={sum(map(len, regions.values()))} cities={total} linked={linked}")
