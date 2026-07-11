"""Swedish dialect regions used for tagging data and stratifying evaluation.

Keys follow the classic six-region classification plus the modern urban
sociolects that motivated this project.
"""

DIALECT_REGIONS = {
    "sydsvenska": "South Swedish — Skåne, Blekinge, southern Halland/Småland (skånska)",
    "gotamal": "Göta dialects — Västergötland, Bohuslän, northern Halland, Dalsland",
    "sveamal": "Svea dialects — Uppland, Södermanland, Västmanland (near-standard)",
    "norrlandska": "Norrland dialects — from Hälsingland north",
    "gotlandska": "Gotland dialects",
    "ostsvenska": "East Swedish — finlandssvenska (Finland Swedish)",
    "dalmal": "Dalecarlian — Dalarna (incl. conservative varieties)",
    "fororts": "Urban contemporary varieties — förortssvenska / multietnolekt "
    "(Rinkeby Swedish etc.); slang-heavy youth speech",
    "unknown": "Unlabeled",
}

# Map of län/city keywords -> region, used to auto-tag scraped metadata.
LOCATION_HINTS = {
    "skåne": "sydsvenska", "malmö": "sydsvenska", "lund": "sydsvenska",
    "helsingborg": "sydsvenska", "blekinge": "sydsvenska", "kristianstad": "sydsvenska",
    "göteborg": "gotamal", "borås": "gotamal", "bohuslän": "gotamal",
    "västergötland": "gotamal", "jönköping": "gotamal",
    "stockholm": "sveamal", "uppsala": "sveamal", "västerås": "sveamal",
    "örebro": "sveamal", "eskilstuna": "sveamal",
    "umeå": "norrlandska", "luleå": "norrlandska", "sundsvall": "norrlandska",
    "skellefteå": "norrlandska", "östersund": "norrlandska", "kiruna": "norrlandska",
    "visby": "gotlandska", "gotland": "gotlandska",
    "helsingfors": "ostsvenska", "åbo": "ostsvenska", "vasa": "ostsvenska",
    "åland": "ostsvenska",
    "falun": "dalmal", "mora": "dalmal", "älvdalen": "dalmal",
    "rinkeby": "fororts", "tensta": "fororts", "rosengård": "fororts",
    "botkyrka": "fororts", "angered": "fororts", "husby": "fororts",
}


def guess_dialect_from_text(text: str) -> str:
    """Best-effort region guess from free-text metadata (titles, descriptions)."""
    lowered = text.lower()
    for keyword, region in LOCATION_HINTS.items():
        if keyword in lowered:
            return region
    return "unknown"
