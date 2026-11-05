import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Served by server.js under /crm/ (behind the shim).
export default defineConfig({ base: '/crm/', plugins: [react()] });
