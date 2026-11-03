<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })
const paths: Record<string, string> = {
  branch: 'M6 8v8m3-11h6a3 3 0 0 1 3 3v8M9 19h6',
  chat: 'M20 11.5a7.5 7.5 0 0 1-7.5 7.5H5l-3 3v-9.5A7.5 7.5 0 0 1 9.5 5h3A7.5 7.5 0 0 1 20 11.5ZM7 10h8m-8 4h5',
  folder: 'M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  chevron: 'm9 5 7 7-7 7',
  down: 'm6 9 6 6 6-6',
  plus: 'M12 5v14M5 12h14',
  check: 'm5 12 4 4L19 6',
  close: 'm6 6 12 12M18 6 6 18',
  menu: 'M4 6h16M4 12h16M4 18h16',
  shield: 'm12 3 8 3v6c0 5-8 9-8 9S4 17 4 12V6l8-3Zm-4 9 3 3 5-6',
  search: 'M15 15 21 21M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
  activity: 'M3 12h4l3-8 4 16 3-8h4',
  book: 'M12 5v15M3 4h5a4 4 0 0 1 4 2 4 4 0 0 1 4-2h5v15h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3V4Z',
  file: 'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9l-6-6Zm0 0v6h6M8 13h8m-8 4h5',
  code: 'm8 7-5 5 5 5m8-10 5 5-5 5m-3-13-2 16',
  external: 'M14 3h7v7m0-7-11 11M10 5H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5',
  refresh: 'M20 7v5h-5M4 17v-5h5m10-3a7 7 0 0 0-12-4L4 8m1 7a7 7 0 0 0 12 4l3-3',
  copy: 'M9 8V4h11v12h-4M4 8h12v13H4V8Z',
  clock: 'M12 8v5l3 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  warning: 'm12 3 10 18H2L12 3Zm0 6v5m0 3v.01',
  settings: 'M4 7h16M4 17h16M8 4v6m8 4v6',
  stop: 'M6 6h12v12H6V6Z',
  info: 'M12 11v6m0-10v.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="app-icon">
    <path :d="paths[name] ?? paths.file" />
    <template v-if="name === 'branch'"><circle cx="6" cy="5" r="3" /><circle cx="6" cy="19" r="3" /><circle cx="18" cy="19" r="3" /></template>
  </svg>
</template>
