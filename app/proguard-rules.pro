# TermBridge ProGuard rules — item R-06.
# Minification is ON in the release build. Every rule below
# exists for a stated reason; no rule may hide a missing
# feature behind an obfuscation claim.

# --- Bouncy Castle + sshj (load-bearing) -------------------
# SshCryptoProvider installs the bundled Bouncy Castle
# provider via Class.forName("org.bouncycastle.jce.provider.
# BouncyCastleProvider") — a reference R8 cannot see, so
# without this keep the release build would strip the
# provider and every SSH connection would fail at runtime.
# sshj additionally resolves factories and key/cipher classes
# reflectively from its configuration tables, and BC's own
# internals rely on reflection and service-style lookups.
# The crypto/transport stack is therefore kept whole; the
# rest of the app (UI, data, domain) is minified normally.
-keep class org.bouncycastle.** { *; }
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**

# sshj's optional ASN.1 / logging integrations are absent by
# design (no asn1j runtime, slf4j is NOP on Android here).
-dontwarn org.bouncycastle.asn1.**
-dontwarn org.slf4j.**

# --- Room ---------------------------------------------------
# Room's generated implementations are referenced from the
# database class by name; the library ships its own consumer
# rules, this is a belt-and-braces keep for the generated
# package only.
-keep class com.pishi.termbridge.data.**_Impl { *; }
