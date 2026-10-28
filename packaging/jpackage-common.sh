# Shared by build-installer.sh and build-linux.sh: the jpackage arguments every platform uses. Needs INPUT and VERSION.
JPACKAGE_COMMON=(--name JobPilot --app-version "$VERSION" --vendor JobPilot --input "$INPUT" --main-jar jobpilot.jar
  --main-class org.springframework.boot.loader.launch.PropertiesLauncher
  --java-options "-Dloader.main=app.jobpilot.desktop.DesktopLauncher" --java-options "-Xmx1g"
  --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.localedata,jdk.management,jdk.net,jdk.security.auth,jdk.security.jgss,jdk.charsets,jdk.zipfs,jdk.naming.dns
  --description "Job applications, tailored and filled in for you")
# Linux .deb: no person in the metadata; the package is called jobpilot and installs under /opt.
JPACKAGE_DEB=(--linux-package-name jobpilot --linux-deb-maintainer "jobpilot@example.invalid"
  --linux-shortcut --linux-menu-group Office --linux-app-category Office --linux-package-deps "")
