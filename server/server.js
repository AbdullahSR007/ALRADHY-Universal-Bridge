const express = require('express');
const http = require('http');
const { Server } = require('socket.io');

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: "*" } });

app.use(express.static('public'));

io.on('connection', (socket) => {
    socket.on('sync-clipboard', (text) => {
        socket.broadcast.emit('update-clipboard', text);
    });
    socket.on('remote-command', (data) => {
        socket.broadcast.emit('execute-command', data);
    });
});

server.listen(process.env.PORT || 3000, () => {
    console.log('🚀 ALRADHY Bridge Server running');
});
