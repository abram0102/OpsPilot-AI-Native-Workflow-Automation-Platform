import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // A self-contained server bundle in .next/standalone, with only the node_modules it actually
  // imports (T32). Without it a runtime image has to carry the whole dependency tree — hundreds
  // of megabytes of build-time packages that never run.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  experimental: {
    serverActions: {
      // Knowledge uploads (T14) run through a Server Action, and the default body cap is 1MB —
      // well under services/api's own 10 MiB document limit. This leaves room for the bytes
      // multipart/form-data adds on top of the file itself (boundaries, part headers).
      bodySizeLimit: "11mb",
    },
  },
};

export default nextConfig;
