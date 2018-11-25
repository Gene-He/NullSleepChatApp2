package edu.rice.comp504.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import edu.rice.comp504.model.cmd.AddRoomCmd;
import edu.rice.comp504.model.cmd.JoinRoomCmd;
import edu.rice.comp504.model.cmd.RemoveRoomCmd;
import org.eclipse.jetty.websocket.api.Session;

import edu.rice.comp504.model.obj.ChatRoom;
import edu.rice.comp504.model.obj.Message;
import edu.rice.comp504.model.obj.User;
import edu.rice.comp504.model.res.*;

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
    //TODO:   controller should call this method, and then call loadUser.  (-Alex)
    public void newSession(Session session) {

        advanceCounter(this.nextUserId);

        userIdFromSession.put(session, this.nextUserId);

    }

    /**
     * Separate method to apply operator ++, but within a synchronized method
     * @param counter
     */
    public synchronized void advanceCounter(int counter) {counter++;}


    /**
     * Get the user if from a session.
     * @param session the session
     * @return the user id binding with session
     */
    public int getUserIdFromSession(Session session) {
        return this.userIdFromSession.get(session);
    }

    /**
     * Determine whether the session exists.
     * @param session the session
     * @return whether the session is still connected or not
     */
    public boolean containsSession(Session session) {
        return this.userIdFromSession.containsKey(session);
    }

    /**
     * Load a user into the environment.
     * @param session the session that requests to called the method
     * @param body of format "name age location school"
     * @return the new user that has been loaded
     */
    public User loadUser(Session session, String body) {
        String[] tokens = body.split(" ");
        int my_id = getUserIdFromSession(session);
        User my_user = new User(my_id, session, tokens[0], (int)(Integer.valueOf(tokens[1])),
                tokens[2], tokens[3], null);
        users.put(my_id, my_user);

        for(ChatRoom room: rooms.values()) {
            if(room.applyFilter(my_user)) my_user.addRoom(room);
        }

        return my_user;
    }


    /**
     * Load a room into the environment.
     * @param session the session that requests to called the method
     * @param body of format "name ageLower ageUpper {[location],}*{[location]} {[school],}*{[school]}"
     * @return the new room that has been loaded
     */
    public ChatRoom loadRoom(Session session, String body) {
        //get this user
        int user_id = getUserIdFromSession(session);
        User my_user = users.get(user_id);

        //check the specifications in the body
        String[] info = body.split(" ");
        Preconditions.checkArgument(info.length == 6 && info[0].equals("create"), "Illegal create room message format: %s", body);
        String roomName = info[1];
        int ageLower = Integer.parseInt(info[2]);
        int ageUpper = Integer.parseInt(info[3]);
        Set locations = Collections.synchronizedSet(new HashSet<>(Arrays.asList(info[4].split(","))));
        Set schools = Collections.synchronizedSet(new HashSet<>(Arrays.asList(info[5].split(","))));

        //construct the room
        advanceCounter(this.nextRoomId);
        ChatRoom my_room = new ChatRoom(roomID, roomName, my_user, ageLower, ageUpper, locations, schools, this);

        if(!my_room.applyFilter(my_user)) {

            //Todo: rejection message

            return null;

        } else {

            rooms.put(this.nextRoomId, my_room);

            //update user's join list
            my_user.addRoom(my_room);
            my_user.moveToJoined(my_room);

            notifyObservers(new AddRoomCmd(body));

            return my_room;
        }
    }

    /**
     * Remove a user with given userId from the environment.
     * @param userId the id of the user to be removed
     */
    public void unloadUser(int userId) {

        //get this user
        User my_user = users.get(userId);
        List<Integer> joined_rooms = my_user.getJoinedRoomIds();

        //remove user from all rooms
        for(Integer room_id : joined_rooms) {
            rooms.get(room_id).removeUser(my_user, "user logged out.")
        }

        //remove user from map
        users.remove(userId);

    }

    /**
     * Remove a room with given roomId from the environment.
     * @param roomId the id of the chat room to be removed
     */
    public void unloadRoom(int roomId) {

        rooms.get(roomId).removeAllUsers();

        //TODO: send message to all users in the room, if there is a way to do this somewhere not inside the
        // room window, which will be disappearing. This may be an action that occurs inside removeAllUsers()
        // in ChatRoom.java.

        //construct and send command to update joined/available lists of all users
        notifyObservers(new RemoveRoomCmd(roomId));

        //delete room from map.
        rooms.remove(roomId);
    }


    /**
     * Make a user join a chat room.
     * @param session the session that requests to called the method
     * @param body of format "roomId"
     */
    public void joinRoom(Session session, String body) {

        //get room from body
        String[] info = body.split(",");
        Preconditions.checkArgument(info.length == 2 && info[0].equals("join"), "Illegal join room message format: %s", body);
        int roomId = Integer.parseInt(info[1]);
        ChatRoom my_room = rooms.get(roomId);

        //get this user
        User my_user = users.get(getUserIdFromSession(session));

        //check room requirements, send rejection if join fails
        boolean join_okay = my_room.applyFilter(my_user);

        if(!join_okay) {

        } else {

            //update joined and available room lists for user
            my_user.moveToJoined(my_room);

            //add user as an observer of the room
            my_room.addUser(my_user);

        }
    }

    /**
     * Make a user volunteer to leave a chat room.
     * @param session the session that requests to called the method
     * @param body of format "roomId"
     */
    public void leaveRoom(Session session, String body) {

        //get room from body
        String[] info = body.split(",");
        Preconditions.checkArgument(info.length == 2 && info[0].equals("join"), "Illegal join room message format: %s", body);
        int roomId = Integer.parseInt(info[1]);
        ChatRoom my_room = rooms.get(roomId);

        //get this user
        User my_user = users.get(getUserIdFromSession(session));

        //update joined/available rooms for this user
        my_user.moveToAvailable(my_room);

        //remove user as observer for this room, broadcast message to room
        my_room.removeUser(my_user, "user left voluntarily.");

        //delete room if this user is the owner
        if(my_room.getUsers().isEmpty()) unloadRoom(roomId);

    }




    //TODO: I question the need for this method. We don't have to allow this. (-Alex)
    /**
     * Make modification on chat room filer by the owner.
     * @param session the session of the chat room owner
     * @param body of format "roomId lower upper {[location],}*{[location]} {[school],}*{[school]}"
     */
    public void modifyRoom(Session session, String body) {

    }

    /**
     * A sender sends a string message to a receiver.
     * @param session the session of the message sender
     * @param body of format "roomId receiverId rawMessage"
     */
    public void sendMessage(Session session, String body) {

    }

    /**
     * Acknowledge the message from the receiver.
     * @param session the session of the message receiver
     * @param body of format "msgId"
     */
    public void ackMessage(Session session, String body) {

    }

    /**
     * Send query result from controller to front end.
     * @param session the session that requests to called the method
     * @param body of format "type roomId [senderId] [receiverId]"
     */
    public void query(Session session, String body) {

    }

    /**
     * Notify the client for refreshing.
     * @param user user expected to receive the notification
     * @param response the information for notifying
     */
    public static void notifyClient(User user, AResponse response) {
    }


    /**
     * Notify session about the message.
     * @param session the session to notify
     * @param response the notification information
     */
    public static void notifyClient(Session session, AResponse response) {
    }

    /**
     * Get the names of all chat room members.
     * @param roomId the id of the chat room
     * @return all chat room members, mapping from user id to user name
     */
    private Map<Integer, String> getUsers(int roomId) {
        return null;
    }

    /**
     * Get notifications in the chat room.
     * @param roomId the id of the chat room
     * @return notifications of the chat room
     */
    private List<String> getNotifications(int roomId) {
        return null;
    }

    /**
     * Get chat history between user A and user B (commutative).
     * @param roomId the id of the chat room
     * @param userAId the id of user A
     * @param userBId the id of user B
     * @return chat history between user A and user B at a chat room
     */
    private List<Message> getChatHistory(int roomId, int userAId, int userBId) {
        return null;
    }
}
