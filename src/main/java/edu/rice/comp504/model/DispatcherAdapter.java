package edu.rice.comp504.model;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import edu.rice.comp504.controller.WebSocketController;
import edu.rice.comp504.model.cmd.*;
import edu.rice.comp504.model.obj.ChatBox;
import org.eclipse.jetty.websocket.api.Session;

import edu.rice.comp504.model.obj.ChatRoom;
import edu.rice.comp504.model.obj.Message;
import edu.rice.comp504.model.obj.User;
import edu.rice.comp504.model.res.*;
import org.eclipse.jetty.websocket.api.WebSocketException;

/**
 * DispatcherAdapter is responsible for communication between controller and other model class
 */
public class DispatcherAdapter extends Observable {

    private AtomicInteger nextUserId;
    private AtomicInteger nextRoomId;
    private AtomicInteger nextMessageId;

    // Maps user id to the user
    private Map<Integer, User> users;

    // Maps room id to the chat room
    private Map<Integer, ChatRoom> rooms;

    // Maps message id to the message
    private Map<Integer, Message> messages;

    // Maps session to user id
    // By calling userIdFromSession.inverse(), we can get a map from userId to session which will be used by notifyClient(user, AResponse)
    private BiMap<Session, Integer> userIdFromSession;

    /**
     * Constructor, initializing all private fields.
     */
    public DispatcherAdapter() {
        this.nextRoomId = new AtomicInteger(0);
        this.nextUserId = new AtomicInteger(0);
        this.nextMessageId = new AtomicInteger(0);
        this.users = new ConcurrentHashMap<>();
        this.rooms = new ConcurrentHashMap<>();
        this.messages = new ConcurrentHashMap<>();
        this.userIdFromSession = Maps.synchronizedBiMap(HashBiMap.create());
    }

    /**
     * Allocate a user id for a new session.
     * @param session the new session
     */
    public void newSession(Session session) {
        userIdFromSession.put(session, nextUserId.getAndIncrement());
    }

    /**
     * Get the user if from a session.
     * @param session the session
     * @return the user id binding with session
     */
    public int getUserIdFromSession(Session session) {
        int res = -1;
        if (userIdFromSession.containsKey(session)) {
            res = userIdFromSession.get(session);
        }
        return res;
    }


    /**
     * Return whether a session exists
     * @param session
     * @return
     */
    public boolean containsSession(Session session) {
        return userIdFromSession.containsKey(session);
    }

    /**
     * Load a user into the environment.
     * @param session the session that requests to called the method
     * @param body of format "name age location school"
     * @return the new user that has been loaded
     */
    public User loadUser(Session session, String body) {
        String[] tokens = body.split(WebSocketController.delimiter);

        // get user ID
        int userId = getUserIdFromSession(session);
        if (userId == -1) {
            return null;
        }

        // parse user age
        int age = 0;
        try {
            age = Integer.valueOf(tokens[2]);
        }
        catch (Exception e) {
            System.out.println("Illegal input for age");
        }

        // create user
        User user = new User(userId, session, tokens[1], age,
                tokens[3], tokens[4], null);
        users.put(userId, user);

        for (ChatRoom room: rooms.values()) {
            if(room.applyFilter(user)) {
                user.addRoom(room);
            }
        }

        // send info to front end
        try {
            session.getRemote().sendString(getChatBoxForUser(userId).toJson());
            session.getRemote().sendString(getRoomsForUser(userId).toJson());
        } catch (IOException exception) {
            System.out.println("Failed when sending room information for new user on login!");
        }

        // user is observer
        addObserver(user);
        return user;
    }


    /**
     * Load a room into the environment.
     * @param session the session that requests to called the method
     * @param body of format "name ageLower ageUpper {[location],}*{[location]} {[school],}*{[school]}"
     * @return the new room that has been loaded
     */
    public ChatRoom loadRoom(Session session, String body) {
        //check the specifications in the body
        String[] info = body.split(WebSocketController.delimiter);
        Preconditions.checkArgument(info.length == 6 && info[0].equals("create"), "Illegal create room message format: %s", body);

        //get this user
        int userId = getUserIdFromSession(session);
        User user = users.get(userId);
        if (user == null) {
            return null;
        }

        String roomName = info[1];

        int ageLower;
        int ageUpper;
        try {
            ageLower = Integer.parseInt(info[2]);
            ageUpper = Integer.parseInt(info[3]);
        } catch (Exception e) {
            System.out.println("Illegal age restrictions");
            return null;
        }

        ChatRoom room = new ChatRoom(nextRoomId.getAndIncrement(), roomName, user, ageLower, ageUpper,
                info[4].split(","), info[5].split(","), this);

        if (room.applyFilter(user)) {
            rooms.put(room.getId(), room);
            //update the room
            room.getUsers().put(user.getId(), user.getName());

            //update user's join list
            user.addRoom(room);
            user.moveToJoined(room);

            //This command now has the DA as a member, and will perform the session.getRemote to send the response.
            IUserCmd cmd = CmdFactory.makeAddRoomCmd(room, this);
            setChanged();
            notifyObservers(cmd);

            try {
                for (int id : users.keySet()) {
                    System.out.println("Send create room response for user: " + users.get(id).getName());
                    users.get(id).getSession().getRemote().sendString(getRoomsForUser(id).toJson());
                }
            }
            catch (IOException exception) {
                System.out.println("Failed when sending room information upon user creating room!");
            }
            catch (WebSocketException e) {
                System.out.println("Session not valid now");
            }
        }
        else {
            room = null;
        }

        return room;
    }

    /**
     * Remove a user with given userId from the environment.
     * @param userId the id of the user to be removed
     */
    public void unloadUser(int userId) {
        User user = users.get(userId);
        if (user == null) {
            return;
        }

        Session session = user.getSession();
        List<Integer> joined_rooms = user.getJoinedRoomIds();

        //remove user from all joined rooms
        for(Integer room_id : joined_rooms) {
            rooms.get(room_id).addNotification(user.getName() + " is logging out.");
            leaveRoom(session, "leave " + room_id);
        }

        users.remove(userId);
        deleteObserver(user);
    }

    /**
     * Remove a room with given roomId from the environment.
     * @param roomId the id of the chat room to be removed
     */
    public void unloadRoom(int roomId) {
        if (!rooms.containsKey(roomId)) {
            return;
        }
        rooms.get(roomId).removeAllUsers();

        //This command now has the DA as a member, and will perform the session.getRemote to send the response.
        IUserCmd cmd = CmdFactory.makeRemoveRoomCmd(rooms.get(roomId), this);
        setChanged();
        notifyObservers(cmd);

        rooms.remove(roomId);
    }


    /**
     * Make a user join a chat room.
     * @param session the session that requests to called the method
     * @param body of format "roomId"
     */
    public void joinRoom(Session session, String body) {
        String[] info = body.split(WebSocketController.delimiter);
        Preconditions.checkArgument(info.length == 2 && info[0].equals("join"), "Illegal join room message format: %s", body);

        int roomId = Integer.parseInt(info[1]);
        ChatRoom room = rooms.get(roomId);
        if (room == null) {
            return;
        }

        //get this user
        User user = users.get(getUserIdFromSession(session));
        if (user == null) {
            return;
        }

        //check room requirements, send rejection if join fails
        if(room.applyFilter(user)) {
            //update joined and available room lists for user
            user.moveToJoined(room);

            //add user as an observer of the room
            room.addUser(user);

            // TODO: Send responses for all users in this room. Better send command to users in a room.
            try {
                //session.getRemote().sendString(getRoomsForUser(user.getId()).toJson());
                for (int id : room.getUsers().keySet()) {
                    System.out.println("Send Join room response for user: " + users.get(id).getName());
                    users.get(id).getSession().getRemote().sendString(getRoomsForUser(id).toJson());
                }
            }
            catch (IOException exception) {
                System.out.println("Failed when sending room information upon user joining room!");
            }
            catch (WebSocketException e) {
                System.out.println("Session not valid now");
            }
        }
    }

    /**
     * Make a user volunteer to leave a chat room.
     * @param session the session that requests to called the method
     * @param body of format "roomId"
     */
    public void leaveRoom(Session session, String body) {
        //get room from body
        String[] info = body.split(WebSocketController.delimiter);
        int roomId = Integer.parseInt(info[1]);
        ChatRoom room = rooms.get(roomId);

        if (room == null) {
            return;
        }

        //get this user
        User user = users.get(getUserIdFromSession(session));

        //update joined/available rooms for this user
        user.moveToAvailable(room);

        //remove user as observer for this room
        room.removeUser(user, " ");

        //add notification to room, with reason
        if(info.length == 3) {
            String new_notification = user.getName();
            String[] notification_words = info[2].split("_");
            for(String s: notification_words) {
                new_notification += (" "+s);
            }
            room.addNotification(new_notification);
        }


        // TODO: send responses to all users in this room. Better send command to users in a room.
        try {
            for (int id : room.getUsers().keySet()) {
                System.out.println("Send Join room and chatBox response for user: " + users.get(id).getName());
                users.get(id).getSession().getRemote().sendString(getRoomsForUser(id).toJson());
                users.get(id).getSession().getRemote().sendString(getChatBoxForUser(id).toJson());
            }
            user.getSession().getRemote().sendString(getRoomsForUser(user.getId()).toJson());
            user.getSession().getRemote().sendString(getChatBoxForUser(user.getId()).toJson());
        } catch (IOException e) {
            System.out.println("Failed when sending room information for user leaving room!");
        } catch (WebSocketException e) {
            System.out.println("Session not valid now");
        }

        //delete room if this user is the owner
        if(room.getOwner() == user) unloadRoom(roomId);
    }

    /**
     * User leaves a chat room voluntarily
     * @param session
     * @param body
     */
    public void voluntaryLeaveRoom(Session session, String body) {
        leaveRoom(session, body+"|left_voluntarily.");
    }

    /**
     * User was ejected from a chat room
     * @param session
     * @param body
     */
    public void ejectFromRoom(Session session, String body){
        leaveRoom(session, body+"|was_ejected_for_violating_chatroom_language_policy.");
    }

    /**
     * A sender sends a string message to a receiver.
     * @param session the session of the message sender
     * @param body of format "roomId receiverId rawMessage"
     */
    public void sendMessage(Session session, String body) {
        System.out.println("sendMessage message: " + body);
        String[] info = body.split(WebSocketController.delimiter);
        int messageId = nextMessageId.getAndIncrement();
        int roomId = Integer.parseInt(info[1]);
        int receiverId = Integer.parseInt(info[2]);
        int senderId = userIdFromSession.get(session);
        String message = info[3];

        if (Arrays.asList(message.split(" ")).contains("hate")) {
            ejectFromRoom(session, "leave|" + roomId);
            return;
        }

        // Update entities in DA.
        Message newMsg = new Message(messageId, roomId, senderId, receiverId, message);
        messages.put(messageId, newMsg);
        rooms.get(roomId).storeMessage(users.get(senderId), users.get(receiverId), newMsg);

        try {
            users.get(receiverId).getSession().getRemote().sendString(getChatBoxForUser(receiverId).toJson());
            AResponse res = new RoomNotificationsResponse("RoomNotifications", roomId, rooms.get(roomId).getName(), senderId, users.get(senderId).getName());
            users.get(receiverId).getSession().getRemote().sendString(res.toJson());
            users.get(senderId).getSession().getRemote().sendString(getChatBoxForUser(senderId).toJson());

        } catch (IOException excpetion) {
            System.out.println("Failed when sending message received confirmation!");
        } catch (WebSocketException e) {
            System.out.println("Session not valid now");
        }
    }

    /**
     * The owner of a room broadcast a message.
     *
     * body string format: "broadcast [roomId] [message]"
     */
    public void broadcastMessage(Session session, String body) {
        System.out.println("broadcast message: " + body);
        String[] info = body.split("\\|");
        int roomId = Integer.parseInt(info[1]);
        String broadCastMsg = info[2];

        // Check if broadcast message has illegal words.
        if (Arrays.asList(broadCastMsg.split(" ")).contains("hate")) {
            // Kick out the owner of this room, basically means unload this room.
            unloadRoom(roomId);
            return;
        }

        // Put broadcast message into notification list.
        rooms.get(roomId).getNotifications().add(broadCastMsg);

        // Send back response to all users in this room.
        rooms.get(roomId).getUsers().keySet().stream().forEach(userId -> constructAndSendResponseForUser(userId));
        // constructAndSendResponseForUser(rooms.get(roomId).getOwner().getId());
    }

    /**
     * Sends the response
     * @param userId
     */
    private void constructAndSendResponseForUser(int userId) {
        try {
            users.get(userId).getSession().getRemote().sendString(getRoomsForUser(userId).toJson());
        } catch (IOException exception) {
            System.out.println("Failed when send updated rooms for user: "+userId);
        }
    }

    /**
     * Acknowledge the message from the receiver.
     * @param session the session of the message receiver
     * @param body of format "msgId"
     */
    public void ackMessage(Session session, String body) {
        String[] info = body.split("\\|");
        int msgId = Integer.parseInt(info[1]);
        Message message = messages.get(msgId);
        message.setIsReceived(true);

        int senderId = message.getSenderId();

        try {
            users.get(senderId).getSession().getRemote().sendString(getChatBoxForUser(senderId).toJson());
        } catch (IOException excpetion) {
            System.out.println("Failed when sending message received confirmation!");
        }
    }

    /**
     * Send query result from controller to front end.
     * @param session the session that requests to called the method
     * @param body of format "type roomId [senderId] [receiverId]"
     */
    public void query(Session session, String body) {
        System.out.println("Query chatBox message: " + body);

        String[] info = body.split("\\|");

        int roomId = Integer.parseInt(info[2]);
        int thisUserId = getUserIdFromSession(session);
        int anotherUserId = Integer.parseInt(info[3]);

        String key = Math.min(thisUserId, anotherUserId) + "&" + Math.max(thisUserId, anotherUserId);
        Map<String, List<Message>> chatHistory = rooms.get(roomId).getChatHistory();
        List<Message> dialogue = null;
        if (!chatHistory.containsKey(key)) {
            dialogue = new ArrayList<>();
        } else {
            dialogue = chatHistory.get(key);
        }
        ChatBox chatBox = new ChatBox(roomId, rooms.get(roomId).getName(), getAnotherUserId(thisUserId, key), getAnotherUserName(thisUserId, key), dialogue);
        List<ChatBox> res = new ArrayList<>();
        res.add(chatBox);
        AResponse response = new UserChatHistoryResponse("UserChatHistory", users.get(thisUserId).getName(), res);

        try {
            session.getRemote().sendString(response.toJson());
        } catch (IOException exception) {
            System.out.println("Failed when sending query chatbox history back to user");
        }

    }

    public AResponse getRoomsForUser(int userId) {
        Set<ChatRoom> availableRooms = users.get(userId).getAvailableRoomIds().stream().map(roomId -> rooms.get(roomId)).collect(Collectors.toSet());
        Set<ChatRoom> joinedRooms    = users.get(userId).getJoinedRoomIds().stream().filter(roomId -> rooms.get(roomId).getOwner().getId() != userId).map(roomId -> rooms.get(roomId)).collect(Collectors.toSet());
        Set<ChatRoom> ownedRooms     = users.get(userId).getJoinedRoomIds().stream().filter(roomId -> rooms.get(roomId).getOwner().getId() == userId).map(roomId -> rooms.get(roomId)).collect(Collectors.toSet());

        return new UserRoomsResponse("UserRooms", userId, users.get(userId).getName(), ownedRooms, joinedRooms, availableRooms);
    }

    public AResponse getChatBoxForUser(int userId) {
        List<ChatBox> chatBoxes = new LinkedList<>();
        for (int roomId : users.get(userId).getJoinedRoomIds()) {
            ChatRoom room = rooms.get(roomId);

            room.getChatHistory().entrySet().stream().filter(entry -> isRelevant(userId, entry.getKey())).forEach(entry -> chatBoxes.add(new ChatBox(roomId, room.getName(), getAnotherUserId(userId, entry.getKey()), getAnotherUserName(userId, entry.getKey()), entry.getValue() )));
        }
        return new UserChatHistoryResponse("UserChatHistory", users.get(userId).getName(), chatBoxes);
    }

    private boolean isRelevant(int userId, String key) {
        String[] users = key.split("&");
        if (userId == Integer.parseInt(users[0]) || userId == Integer.parseInt(users[1]))
            return true;

        return false;
    }

    private String getAnotherUserName(int userId, String key) {
        return users.get(getAnotherUserId(userId, key)).getName();
    }

    private int getAnotherUserId(int userId, String key) {
        String[] users = key.split("&");
        if (userId == Integer.parseInt(users[0])) {
            return Integer.parseInt(users[1]);
        } else {
            return Integer.parseInt(users[0]);
        }
    }

}