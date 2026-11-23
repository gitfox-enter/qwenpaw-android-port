/**
 * Embedded-runtime build flag. The enhanced build bundles the proot server
 * inside the app and is intentionally locked to the local server: no remote
 * pairing, no Platform login. The official upstream app (which connects to
 * remote QwenPaw instances) keeps this false.
 */
export const EMBEDDED_AUTO_CONNECT = true;