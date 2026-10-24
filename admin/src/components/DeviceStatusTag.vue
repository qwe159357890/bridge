<template>
  <span class="status-tag">
    <span class="dot" :class="online ? 'dot-online' : 'dot-offline'" />
    <span :class="online ? 'on' : 'off'">{{ online ? '在线' : '离线' }}</span>
  </span>
</template>

<script setup lang="ts">
defineProps<{ online: boolean }>()
</script>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
}
.on {
  color: var(--success);
  font-weight: 500;
}
.off {
  color: var(--info);
}
</style>
