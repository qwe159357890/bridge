<template>
  <el-select
    :model-value="modelValue"
    :placeholder="placeholder"
    filterable
    @update:model-value="(v: string) => $emit('update:modelValue', v)"
  >
    <el-option
      v-if="withAll"
      key="__all__"
      label="📋 全部设备（聚合）"
      :value="''"
    />
    <el-option
      v-for="d in devices"
      :key="d.device_id"
      :label="`${d.device_name}（${d.device_id}）${d.online ? '' : ' · 离线'}`"
      :value="d.device_id"
      :disabled="onlyOnline && !d.online"
    />
  </el-select>
</template>

<script setup lang="ts">
import type { Device } from '@/types'

defineProps<{
  devices: Device[]
  modelValue: string
  placeholder?: string
  onlyOnline?: boolean
  /** 顶部加「全部设备（聚合）」选项（value=''），短信页聚合模式用 */
  withAll?: boolean
}>()

defineEmits<{ (e: 'update:modelValue', value: string): void }>()
</script>
