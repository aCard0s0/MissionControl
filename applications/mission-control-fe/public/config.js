// Mission Control runtime config — dev default.
// In the combined Docker image this file is served dynamically by
// mission-control-server from MC_* environment variables, so one image
// serves any deployment.
window.__MC_CONFIG__ = {
  dataMode: 'mock',                              // 'mock' (demo data) | 'live' (backend API)
  apiBaseUrl: '',                                // '' = same origin (dev proxy / combined image)
  dockerSocket: 'unix:///var/run/docker.sock',   // default local daemon endpoint
};
