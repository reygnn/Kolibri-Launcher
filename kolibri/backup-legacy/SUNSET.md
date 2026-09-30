# Sunset of :kolibri:backup-legacy

`SUNSET` holds the date (YYYY-MM-DD) from which `checkConventions` warns that this module
is due for removal: three months after the first release that contains Phase 2a
(SPEC_NYX_REWRITE E5a). Set it to the real date when 2a is released.

Removing the module: delete this directory, drop it from `settings.gradle.kts`, from
`:kolibri:app`'s dependencies and from `tools/conventions/kolibri.conf`. Nothing else
changes — without a bound reader the engine answers old archives with
"older version, no longer supported".
