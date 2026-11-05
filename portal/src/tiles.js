// Roles that grant access but do not render as an application tile.
export const NON_TILE_ROLES = ['USA'];

// Role -> tile definition. Unknown roles get a generic tile.
export const TILE_CATALOG = {
  RETAILUI: { title: 'Retail UI', desc: 'Retail storefront console', icon: '🛍️', color: '#db2777', launchUrl: 'http://localhost:3001/' },
  CRM: { title: 'CRM', desc: 'Customer relationship management', icon: '👥', color: '#059669', launchUrl: 'http://localhost:3002/' },
  DAM_OP: { title: 'DAM Operations', desc: 'Digital asset management ops', icon: '🗂️', color: '#d97706' },
  CRR: { title: 'CRR', desc: 'Credit risk reporting', icon: '📊', color: '#7c3aed' },
};

export const tileFor = (role) =>
  TILE_CATALOG[role] || { title: role, desc: 'Application', icon: '🧩', color: '#475569' };
