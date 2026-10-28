<template>
  <el-select
    :model-value="displayValue"
    :placeholder="placeholder"
    filterable
    @update:model-value="(v: string) => $emit('update:modelValue', v === ALL ? '' : v)"
  >
    <el-option
      v-if="withAll"
      key="__all__"
      label="📋 全部设备（聚合）"
      :value="ALL"
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
import { computed } from 'vue'
import type { Device } from '@/types'

const props = defineProps<{
  devices: Device[]
  modelValue: string
  placeholder?: string
  onlyOnline?: boolean
  /** 顶部加「全部设备（聚合）」选项，对外 v-model 契约：'' = 全部设备 */
  withAll?: boolean
}>()

defineEmits<{ (e: 'update:modelValue', value: string): void }>()

/** 哨兵值：el-select 把空串 modelValue 视为「未选中」（只显示 placeholder），
 * 不显示「全部设备」选项文案——故对外 '' 在内部映射为哨兵值。 */
const ALL = '__all__'
const displayValue = computed(() =>
  props.withAll && props.modelValue === '' ? ALL : props.modelValue,
)
</script>
