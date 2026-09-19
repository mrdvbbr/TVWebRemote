#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <ctype.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <linux/input.h>
#include <linux/uinput.h>

static int uinput_fd = -1;

static const int supported_keys[] = {
    KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_ENTER,
    KEY_BACK, KEY_HOMEPAGE, KEY_MENU, KEY_VOLUMEUP, KEY_VOLUMEDOWN,
    KEY_MUTE, KEY_POWER, KEY_PLAYPAUSE, KEY_REWIND, KEY_FASTFORWARD,
    KEY_CHANNELUP, KEY_CHANNELDOWN,
    KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9,
    KEY_BACKSPACE, KEY_SPACE
};

static int init_uinput(void) {
    uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) {
        perror("open /dev/uinput");
        return -1;
    }

    if (ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(uinput_fd, UI_SET_EVBIT, EV_SYN) < 0) {
        perror("ioctl UI_SET_EVBIT");
        close(uinput_fd);
        uinput_fd = -1;
        return -1;
    }

    int nkeys = sizeof(supported_keys) / sizeof(supported_keys[0]);
    for (int i = 0; i < nkeys; i++) {
        ioctl(uinput_fd, UI_SET_KEYBIT, supported_keys[i]);
    }

    struct uinput_user_dev uidev;
    memset(&uidev, 0, sizeof(uidev));
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "TVRemote Virtual Controller");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor = 0x1234;
    uidev.id.product = 0x5678;
    uidev.id.version = 1;

    if (write(uinput_fd, &uidev, sizeof(uidev)) < 0) {
        perror("write uidev");
        close(uinput_fd);
        uinput_fd = -1;
        return -1;
    }

    if (ioctl(uinput_fd, UI_DEV_CREATE) < 0) {
        perror("ioctl UI_DEV_CREATE");
        close(uinput_fd);
        uinput_fd = -1;
        return -1;
    }

    return 0;
}

static int android_to_linux_key(int code) {
    switch (code) {
        // Android KeyEvent to Linux Scancode
        case 19: return KEY_UP;          // 103 (DPAD_UP)
        case 20: return KEY_DOWN;        // 108 (DPAD_DOWN)
        case 21: return KEY_LEFT;        // 105 (DPAD_LEFT)
        case 22: return KEY_RIGHT;       // 106 (DPAD_RIGHT)
        case 23: return KEY_ENTER;       // 28  (DPAD_CENTER)
        case 66: return KEY_ENTER;       // 28  (ENTER)
        case 4:  return KEY_BACK;        // 158 (BACK)
        case 3:  return KEY_HOMEPAGE;    // 172 (HOME)
        case 82: return KEY_MENU;        // 139 (MENU)
        case 24: return KEY_VOLUMEUP;    // 115 (VOLUME_UP)
        case 25: return KEY_VOLUMEDOWN;  // 114 (VOLUME_DOWN)
        case 164: return KEY_MUTE;       // 113 (VOLUME_MUTE)
        case 26: return KEY_POWER;       // 116 (POWER)
        case 85: return KEY_PLAYPAUSE;   // 164 (MEDIA_PLAY_PAUSE)
        case 89: return KEY_REWIND;      // 168 (MEDIA_REWIND)
        case 90: return KEY_FASTFORWARD; // 208 (MEDIA_FAST_FORWARD)
        case 166: return KEY_CHANNELUP;  // 402 (CHANNEL_UP)
        case 167: return KEY_CHANNELDOWN;// 403 (CHANNEL_DOWN)
        case 7:  return KEY_0;           // 11  (0)
        case 8:  return KEY_1;           // 2   (1)
        case 9:  return KEY_2;           // 3   (2)
        case 10: return KEY_3;           // 4   (3)
        case 11: return KEY_4;           // 5   (4)
        case 12: return KEY_5;           // 6   (5)
        case 13: return KEY_6;           // 7   (6)
        case 14: return KEY_7;           // 8   (7)
        case 15: return KEY_8;           // 9   (8)
        case 16: return KEY_9;           // 10  (9)
        case 67: return KEY_BACKSPACE;   // 14  (DEL/BACKSPACE)
        case 62: return KEY_SPACE;       // 57  (SPACE)
        default: return code;
    }
}

static void send_linux_key(int scancode) {
    if (uinput_fd < 0 || scancode <= 0) return;

    struct input_event ev[2];

    // KEY DOWN
    memset(ev, 0, sizeof(ev));
    ev[0].type = EV_KEY;
    ev[0].code = (__u16)scancode;
    ev[0].value = 1;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;
    write(uinput_fd, ev, sizeof(ev));

    // Brief press duration for reliable detection
    usleep(20000); // 20ms

    // KEY UP
    memset(ev, 0, sizeof(ev));
    ev[0].type = EV_KEY;
    ev[0].code = (__u16)scancode;
    ev[0].value = 0;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    ev[1].value = 0;
    write(uinput_fd, ev, sizeof(ev));
}

static void process_command(char *buf) {
    int len = strlen(buf);
    while (len > 0 && (buf[len - 1] == '\r' || buf[len - 1] == '\n' || buf[len - 1] == ' ')) {
        buf[--len] = '\0';
    }

    if (strcmp(buf, "PING") == 0) {
        return;
    }

    char *p = buf;
    if (strncmp(p, "SEQ:", 4) == 0) {
        p += 4;
    }

    char *token = strtok(p, " ,");
    while (token != NULL) {
        int code = atoi(token);
        if (code > 0) {
            int scancode = android_to_linux_key(code);
            send_linux_key(scancode);
            usleep(40000); // 40ms inter-key gap
        }
        token = strtok(NULL, " ,");
    }
}

int main(int argc, char *argv[]) {
    if (init_uinput() < 0) {
        fprintf(stderr, "Failed to initialize /dev/uinput\n");
        return 1;
    }

    // Give EventHub a moment to detect and register the device on startup
    usleep(300000); // 300ms

    if (argc >= 2 && strcmp(argv[1], "--daemon") == 0) {
        int port = 7777;
        if (argc >= 3) port = atoi(argv[2]);

        int sock = socket(AF_INET, SOCK_DGRAM, 0);
        if (sock < 0) {
            perror("socket");
            return 2;
        }

        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons((uint16_t)port);
        addr.sin_addr.s_addr = INADDR_ANY;

        if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            perror("bind");
            return 3;
        }

        printf("tvkey uinput daemon running on UDP %d\n", port);
        fflush(stdout);

        char buf[256];
        while (1) {
            struct sockaddr_in client_addr;
            socklen_t addrlen = sizeof(client_addr);
            ssize_t n = recvfrom(sock, buf, sizeof(buf) - 1, 0, (struct sockaddr *)&client_addr, &addrlen);
            if (n > 0) {
                buf[n] = '\0';
                process_command(buf);
                sendto(sock, "OK", 2, 0, (struct sockaddr *)&client_addr, addrlen);
            }
        }
    } else if (argc >= 2) {
        for (int i = 1; i < argc; i++) {
            int code = atoi(argv[i]);
            int scancode = android_to_linux_key(code);
            send_linux_key(scancode);
            if (i + 1 < argc) usleep(50000);
        }
    } else {
        fprintf(stderr, "Usage: %s <keycode...> | %s --daemon [port]\n", argv[0], argv[0]);
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        return 1;
    }

    ioctl(uinput_fd, UI_DEV_DESTROY);
    close(uinput_fd);
    return 0;
}
