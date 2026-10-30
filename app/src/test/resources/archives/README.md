# Encrypted 7z fixtures

Generated with the official public-domain 7zr 26.04 CLI. Both contain only
`secret.txt` with the UTF-8 text `encrypted 7z content` (no newline).
Password: `correct` (public test data, not a credential).

```
7zr a -t7z -pcorrect -mhe=on encrypted-headers.7z secret.txt
7zr a -t7z -pcorrect -mhe=off encrypted-data.7z secret.txt
```

These independent fixtures verify both encrypted-header and encrypted-data
7z extraction; Commons Compress cannot create encrypted 7z files itself.
