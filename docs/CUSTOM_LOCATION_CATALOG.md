# Custom Location: geographic catalog

`V2rayNG/app/src/main/assets/location_catalog.json` is **geographic metadata only**. It is not an
inventory of exit servers. A city being listed says nothing about whether any VPN endpoint exists there.

## Contents

| Part | Source | Identifier |
|---|---|---|
| Countries | **Not bundled.** Derived at runtime from `java.util.Locale.getISOCountries()`; names come from the platform in the device language. | ISO 3166-1 alpha-2 |
| Regions (first level only, 3,590) | Python `pycountry` 26.2.16, which repackages Debian `iso-codes` | ISO 3166-2 (e.g. `US-NY`) |
| Cities (809, population >= 500,000) | npm `all-the-cities` 3.1.0, a repackaging of GeoNames | GeoNames id |

Size: 128 KB raw, about 55 KB compressed in the APK.

## Licences and attribution

* GeoNames data: **CC BY 4.0**, <https://www.geonames.org/>. Attribution is required. It is shown in the app
  (Custom Location → Profile, string `cl_catalog_attribution`, all 10 locales) and recorded here.
* `iso-codes` / `pycountry` data: **LGPL-2.1-or-later**, compatible with this project's GPL-3.0.
* `all-the-cities` code: MIT. It is only a build-time tool and is not shipped.
* The generator scripts in `tools/location_catalog/` are part of this repository and not shipped in the APK.

`all-the-cities` 3.1.0 was used as packaged; its `cities.pbf` has SHA-256
`24284582eb1844c783b5065b380e97ecf803dab19f9efdbc3d1a7a9d286a97ab`. The date of the underlying GeoNames
dump is **not asserted** because the package does not state it. The `meta.generated` field in the asset is
the date the file was generated, not the age of the data.

## Update procedure

```
npm install all-the-cities@<version>          # in a scratch directory
node tools/location_catalog/export_cities.js 500000 > cities.json
pip install pycountry
python3 tools/location_catalog/generate_catalog.py cities.json \
    V2rayNG/app/src/main/assets/location_catalog.json 500000
```

Then run the unit tests; `GeoCatalogTest.bundledCatalogParsesCleanly` and
`bundledCatalogHasNoAmbiguousLabelsAndOnlyValidCountries` validate the new file. Update the pinned
versions and hash above. The last argument is the population threshold; lowering it adds cities and size.

## Known limitations

* **Region names and city names are English only.** Country names are localized; regions and cities are not.
* **Only 174 of 809 cities are linked to an ISO 3166-2 region.** GeoNames `admin1` codes equal ISO suffixes
  only for some countries (for example the US). Links are made only when the resulting code exists in ISO
  3166-2, never guessed. Unlinked cities are shown under the country and are not offered under a region.
* **No metro areas.** Entries are GeoNames populated places. For New York, GeoNames lists the boroughs
  (Brooklyn, Queens, Manhattan, The Bronx) as separate places next to "New York City". "Northern
  Virginia / Ashburn" is below the population threshold and is absent.
* Population-based cut-off: Miami is absent (below the threshold in this data) while Seattle, Boston, Dallas and Chicago are present.
* Coordinates are place centroids, not precise.
* The data goes stale. The UI shows the snapshot date and says so.
* ISO 3166 changes (new/renamed subdivisions) are picked up only on regeneration.
