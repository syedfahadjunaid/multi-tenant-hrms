/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Keycloak realm URL. Defaults to the local realm. */
  readonly VITE_OIDC_AUTHORITY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
