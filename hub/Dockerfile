FROM node:24-alpine

ENV NODE_ENV=production \
    DATA_DIR=/data

WORKDIR /app
COPY package.json ./
COPY src ./src

# /data is normally a bind mount (owned by uid 1000 on the Pi, the same uid as "node").
RUN mkdir -p /data && chown node:node /data
USER node

EXPOSE 8774 8775

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8774/health || exit 1

CMD ["node", "src/server.js"]
