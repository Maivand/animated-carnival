# Legal & ethical notes on data collection

This project's YouTube pipeline is a research tool. Before running it at
scale, whoever operates it is responsible for:

- **Copyright / TDM**: In the EU, text-and-data-mining exceptions (DSM
  directive art. 3/4) may apply, but rightsholders can opt out; commercial use
  is more restricted than research use. Get legal advice for a commercial
  model.
- **Platform terms**: Automated downloading may violate YouTube's ToS. Keep
  volumes modest, use the built-in rate limiting, and prefer content under
  Creative Commons license (yt-dlp can filter: add
  `--match-filter "license='Creative Commons Attribution license (reuse allowed)'"`).
- **GDPR / personal data**: Voice is personal data. Keep only audio + text
  needed for training, don't build speaker profiles, honor deletion requests,
  and document your lawful basis (research exemptions may apply).
- **Attribution & takedowns**: Keep the `downloaded.txt` archive and info-JSON
  sidecars so any source can be identified and removed from the corpus, with
  retraining possible after removal.

The open datasets (RixVox, NST, Common Voice, FLEURS) have explicit licenses —
check each on Hugging Face before redistribution of trained weights.
