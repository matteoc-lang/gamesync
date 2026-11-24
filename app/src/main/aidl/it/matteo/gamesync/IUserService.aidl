package it.matteo.gamesync;
interface IUserService {
    void destroy() = 1;
    void exit() = 2;
    String copyTree(String src, String dst, boolean mirror, String trashRoot) = 3;
    String topPackage() = 4;
    String shell(String cmd) = 5;
    String listDirs(String path) = 6;
}
