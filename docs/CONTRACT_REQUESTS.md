# Contract requests (append-only, everyone)

Add a request when you need something from another person's module or a contract change.
Format: `- [time] [from → to] what you need and why — status: OPEN/DONE/REJECTED`

- [23:45] [all] Example: "[B → C] manifest needs sqliteSha256 to verify downloads — status: DONE"
- [A → D] `SosService.sendSos()` can only text every contact, so "Try again" after a partial failure re-texts contacts who already got it. Please add `sendSos(only: List<String>? = null)` (phone numbers) so A can retry just the failed ones — status: OPEN
