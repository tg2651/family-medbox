#!/bin/sh
cd /app
if [ ! -d node_modules ]; then
  npm install --omit=dev --registry=https://registry.npmmirror.com
fi
exec node server.js
