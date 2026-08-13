# Fabric 26.x shared sources

Fabric 26.x modules compose their sources from this directory through
`platforms/fabric/26.x/shared.gradle`.

- `common`: code and resources shared by every 26.x build.
- `api/26.1-26.1.2`: client API calls used by 26.1, 26.1.1, and 26.1.2.
- `api/26.2`: client API calls introduced by 26.2.

Each version directory contains only dependency coordinates and applies the shared
build script. Keep version-specific differences in the smallest applicable API
layer instead of copying a complete source tree.
