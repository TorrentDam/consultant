{
  description = "Film consultant — agentic WebSocket service";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "aarch64-darwin" "aarch64-linux" "x86_64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      devShells = forAllSystems (pkgs:
        let
          jdk = pkgs.temurin-bin-21;

          # Pin Mill's own JVM to the same JDK, rather than nixpkgs' default `jre`
          # that the mill derivation would otherwise bake in.
          mill = pkgs.mill.override { jre = jdk; };

          # Headless Metals: never prompt to import the build, talk to Mill's
          # BSP server directly instead of exporting to Bloop, and use Scala 3
          # best-effort compilation for better diagnostics on broken code.
          metals = pkgs.metals.override {
            jre = jdk;
            extraJavaOpts = "-XX:+UseG1GC -XX:+UseStringDeduplication -Xss4m -Xms100m "
              + "-Dmetals.auto-import-builds=all "
              + "-Dmetals.default-bsp-to-build-tool=true "
              + "-Dmetals.target-build-tool=mill "
              + "-Dmetals.enable-best-effort=true "
              # Bare LSP clients (like opencode) don't implement
              # window/showMessageRequest; unanswered, it aborts Metals'
              # initialization. This flag makes Metals auto-answer such
              # prompts with their default action instead.
              + "-Dmetals.disable-show-message-request=true";
          };

          # Scala Native toolchain: LLVM/Clang for ahead-of-time compilation
          # and the Boehm GC / zlib for the optional native runtime libraries.
          clang = pkgs.llvmPackages_18.clang;
        in {
          default = pkgs.mkShell {
            packages = [ mill metals jdk clang pkgs.boehmgc pkgs.zlib ];
            JAVA_HOME = "${jdk.home}";
            # Mill's BSP mode defaults to a separate `.bsp/mill-bsp-out/` output
            # directory. Without this, the LSP (via Metals/BSP) and the CLI
            # (`mill compile`) would each maintain their own incremental
            # compilation cache and compile everything twice.
            MILL_NO_SEPARATE_BSP_OUTPUT_DIR = "1";
          };
        });
    };
}
