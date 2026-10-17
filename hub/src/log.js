// One line per event, local time (TZ is set in docker-compose). Never pass tokens or codes in here.

const pad = (n) => String(n).padStart(2, '0');

function timestamp(d = new Date()) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function createLogger(write = (line) => process.stdout.write(line + '\n')) {
  return (message) => write(`${timestamp()} ${message}`);
}
