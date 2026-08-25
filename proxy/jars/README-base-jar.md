# Base jar provenance

`ViaProxy-3.4.13-snapshot-b913-vb12644-base.jar`

ViaProxy CI build **b913** (`git-ViaProxy-3.4.13-SNAPSHOT:c4e6ea9`), with the
bundled ViaBedrock replaced by a build of **RaphiMC/ViaBedrock `update/1.26.40`**
at commit `0e87f93` (2026-08-19T08:35:50+02:00), which targets **Bedrock 1.26.44, protocol 2168**.

The stock b913 bundles ViaBedrock from `main`, which is still Bedrock 1.26.30 /
protocol 1001. Realms have updated past that, and a 1001 client is refused at
login with `LOGIN_FAILED_CLIENT_OLD` ("Outdated client!"), so the stock jar
cannot reach a realm at all.

Rebuild:

    git clone --branch update/1.26.40 https://github.com/RaphiMC/ViaBedrock
    cd ViaBedrock && ./gradlew build
    # replace net/raphimc/viabedrock/** and assets/viabedrock/** in the b913 jar

Both projects declare the same artifact version (`ViaBedrock:0.0.29-SNAPSHOT`),
so this is a drop-in swap; the only build.gradle difference between `main` and
the update branch is a `compileOnly` guava version, which does not apply at
runtime.

Drop this file and the swap once the update branch is merged and ViaProxy CI
ships a build that bundles it.
