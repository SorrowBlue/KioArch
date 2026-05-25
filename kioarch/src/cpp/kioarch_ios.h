/*
 * Copyright 2026 SorrowBlue
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef KIOARCH_IOS_H
#define KIOARCH_IOS_H

#include <stdint.h>
#include <stddef.h>

// Input seekable stream callbacks
typedef int32_t (*kio_read_fn)(void *opaque, uint8_t *buf, int32_t len);
typedef void (*kio_seek_fn)(void *opaque, int64_t pos);
typedef int64_t (*kio_position_fn)(void *opaque);
typedef int64_t (*kio_length_fn)(void *opaque);

// Output stream callback (kotlinx.io.Sink.write equivalent)
typedef void (*kio_write_fn)(void *opaque, const uint8_t *buf, int32_t len);

typedef struct {
    kio_read_fn read;
    kio_seek_fn seek;
    kio_position_fn position;
    kio_length_fn length;
    void *opaque; // Kotlin's StableRef of SeekableSource
} kio_source_t;

typedef struct {
    kio_write_fn write;
    void *opaque; // Kotlin's StableRef of kotlinx.io.Sink
} kio_sink_t;

// Unified archive entry structure
typedef struct {
    int32_t index;
    const char *name;
    int64_t size;
    int32_t is_dir;
    uint32_t crc;
} kio_entry_t;

// iOS specific API functions
uint64_t kio_open_archive(kio_source_t source, char *err_msg, int32_t err_msg_len);
void kio_close_archive(uint64_t handle);

int32_t kio_get_entry_count(uint64_t handle);
int32_t kio_get_entry(uint64_t handle, int32_t index, kio_entry_t *entry);

int32_t kio_extract_entry(uint64_t handle, int32_t index, kio_sink_t sink, char *err_msg, int32_t err_msg_len);

uint64_t kio_get_resident_memory(void);

#endif // KIOARCH_IOS_H
