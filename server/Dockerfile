FROM node:22-bookworm-slim

ENV NODE_ENV=production
WORKDIR /app

# better-sqlite3 and argon2 may need native build tooling on platforms
# without a matching prebuilt binary.
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*

COPY package*.json ./
RUN npm install --omit=dev && npm cache clean --force

COPY src ./src
RUN mkdir -p /data && chown -R node:node /app /data
USER node

ENV HOST=0.0.0.0
ENV PORT=8080
ENV DATA_DIR=/data
EXPOSE 8080
CMD ["node", "src/server.js"]
