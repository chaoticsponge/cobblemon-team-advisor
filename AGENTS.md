# Release requirements

- Keep downloadable builds for both Cobblemon 1.7.3 and 1.8.1 for every future mod update. Preserve older GitHub releases and their assets.
- Build and run checks separately against each target's Cobblemon jar. Use `-PcobblemonVersion=<version> -PcobblemonJar=<matching jar path>` to label each artifact.
- Publish both `cobblemon-team-advisor-<mod version>-cobblemon-1.7.3.jar` and `cobblemon-team-advisor-<mod version>-cobblemon-1.8.1.jar` on the same GitHub release. Clearly identify which download matches each Cobblemon version; players install only one.
- Do not silently drop either target or publish a failing build. Report any compatibility blocker and preserve the last working download.
