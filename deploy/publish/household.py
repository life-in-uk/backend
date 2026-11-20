"""Pinned Household editorial handoff. This batch is eligible for draft import only."""

from hashlib import sha256

from promote import BatchIdentity, LOCALES, canonical_form, require

CATEGORY = "household-services"
IDENTITY = BatchIdentity("household-20261120", "household-draft-v1", "2026-11-20T21:28:14Z")
MANIFEST = "EDITORIAL-MANIFEST.json"
MANIFEST_SHA256 = "1588d4408582fc7c90eb7bcf0c2c453870b71b4a151e099b458b055d5e4ed4ab"
SLUGS = frozenset({
    "uk-home-insurance-buildings-contents",
    "uk-home-emergency-cover-vs-service-plan",
    "uk-car-insurance-cover-before-driving",
    "uk-breakdown-cover-roadside-assistance",
    "uk-pet-insurance-vet-fees",
    "uk-broadband-contracts-switching",
    "uk-energy-tariffs-switching",
})


def package_names():
    return {MANIFEST} | {f"guides/{slug}/{locale}.json" for slug in SLUGS for locale in LOCALES}


def load_package(directory, read_json):
    raw = (directory / MANIFEST).read_bytes()
    require(sha256(raw).hexdigest() == MANIFEST_SHA256, "Household editorial manifest does not match.")
    manifest = read_json(raw)
    articles = manifest["articles"]
    require(manifest["proposedCategory"] == CATEGORY and manifest["status"] == "EDITORIAL_DRAFT"
            and manifest["articleCount"] == len(articles) == len(SLUGS)
            and manifest["variantCount"] == len(SLUGS) * len(LOCALES)
            and {article["slug"] for article in articles} == SLUGS,
            "Household article allowlist or manifest status does not match.")
    package = {}
    for article in articles:
        slug = article["slug"]
        records = article["locales"]
        require(set(records) == set(LOCALES)
                and article["sourceContentSha256"] == records["zh-CN"]["contentSha256"],
                f"{slug}: Household locale set or Chinese source linkage does not match.")
        guides = {}
        for locale in LOCALES:
            record = records[locale]
            path = f"guides/{slug}/{locale}.json"
            raw = (directory / path).read_bytes()
            require(record["file"] == path and sha256(raw).hexdigest() == record["fileSha256"],
                    f"{slug}/{locale}: Household Guide file hash does not match.")
            guide = read_json(raw)
            require(guide["slug"] == slug and guide["category"] == CATEGORY
                    and guide["status"] == "DRAFT" and guide["publishedAt"] is None,
                    f"{slug}/{locale}: expected a private Household draft.")
            content_hash = sha256(canonical_form(guide).encode()).hexdigest()
            require(content_hash == record["contentSha256"],
                    f"{slug}/{locale}: Household canonical content hash does not match.")
            guides[locale] = (guide, content_hash)
        package[slug] = guides
    return package
