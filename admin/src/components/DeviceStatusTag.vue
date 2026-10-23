<template>
  <el-tag :type="online ? 'success' : 'info'" size="small">
    {{ online ? '在线' : '离线' }}
  </el-tag>
</template>

<script setup lang="ts">
defineProps<{ online: boolean }>()
</script>
