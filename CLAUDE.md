# opcua-adapter — Claude Code

@AGENTS.md

## Local development

- **Toolchain (this machine).** JDK 25 + Maven live under `C:\Users\breis\tools\{jdk,maven}` (not on
  PATH). Build with `JAVA_HOME=C:\Users\breis\tools\jdk` and `C:\Users\breis\tools\maven\bin\mvn`.
- **Build against the sibling core library.** Maven resolves `com.mbreissi.edgecommons:edgecommons`
  from GitHub Packages by version (`edgecommons.version`, default `0.5.0`). For local core work,
  `cd ../core/libs/java && mvn install -DskipTests`, then build here with
  `mvn -Dedgecommons.version=<the version that install printed> package` — Maven's local-repo-first
  resolution then satisfies it from `~/.m2` without contacting GitHub Packages. There is no
  `.cargo`-style path-override file for Java; the `mvn install` step is the whole local-dev story.
- **Gates.** `mvn verify` runs the unit suite and the JaCoCo 90% line-coverage `check`. Don't lower the
  gate or widen the excludes to pass — add tests (see `AGENTS.md` / `DESIGN.md` for what the excludes
  scope out and why). `src/test` needs no live server; the live Milo seam is validated by `validation/`
  against KEPServerEX and by the Greengrass lab.
- **Package the deployable artifact** with `mvn package` → `target/opcua-adapter-1.0.0.jar` (the kebab
  jar the `recipe.yaml` artifact URI and `Dockerfile` reference; the Greengrass component name stays
  `com.mbreissi.edgecommons.OpcUaAdapter`).
